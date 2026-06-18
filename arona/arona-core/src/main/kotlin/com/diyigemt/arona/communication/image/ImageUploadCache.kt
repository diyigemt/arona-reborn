@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.diyigemt.arona.communication.image

import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.FriendUser
import com.diyigemt.arona.communication.contact.Group
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.communication.message.getMediaUrlFromMediaInfo
import com.diyigemt.arona.database.AronaImageUploadCacheTable
import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.now
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * 消息场景. 腾讯官方约定: 群聊与单聊的图片上传凭证不可互用, 但同一场景下凭证可跨会话复用.
 */
enum class ImageCacheScene {
  /** 群聊. 凭证可跨群复用. */
  GROUP,

  /** 单聊 (C2C). 凭证可跨用户复用. */
  C2C,
}

/**
 * 框架统一的图片上传凭证缓存. 数据库持久化, 替代各插件自有的内存 / 表实现.
 *
 * 缓存的是 [Contact.uploadImage] 返回的腾讯 `resourceId` (file_info): 同一张逻辑图片在 ttl 内
 * 复用同一 resourceId 重新发送, 免去重复上传. 凭证 ttl 通常约 15 天, 即缓存的一般有效期.
 *
 * ## 作用域
 * 默认在 `(AppID, 消息场景)` 维度共享:
 * - [ImageCacheScene.GROUP] 凭证跨群复用;
 * - [ImageCacheScene.C2C] 凭证跨用户复用;
 * - 频道 (Guild / Channel) 及群成员 (GroupMember) 等其它 [Contact] 不参与缓存
 *   ([find] 返回 null, [put] 不写入, [getOrUpload] 直接上传不缓存). 频道图片走 URL 而非上传凭证.
 *
 * ## 并发
 * 单进程内 [getOrUpload] 以固定槽位 [Mutex] 做 single-flight 去重; 不同身份哈希碰撞只会短暂串行,
 * 不影响正确性, 也不会让锁容器无界增长. 多实例部署下依赖唯一索引 + upsert 保证最终单条记录,
 * 允许极少量跨进程重复上传.
 *
 * ## 容错
 * 数据库读失败按未命中处理, 写失败仍返回已上传的图片; 上传失败与协程取消向调用方透传.
 */
object ImageUploadCache {
  /** 凭证临过期前的安全提前量: 提前该时长视为不可用, 规避时钟漂移与发送延迟. */
  private const val SAFE_WINDOW_SECONDS = 30L * 60L

  /** ttl == 0 (腾讯定义为长期有效) 时的运营上限, 避免失效凭证永久驻留. */
  private const val LONG_TERM_CAP_SECONDS = 365L * 24L * 60L * 60L

  /** single-flight 锁槽数量. 固定容量, 不随 key 数量增长. */
  private const val STRIPE_COUNT = 64

  private const val CLEANUP_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L

  private val uploadLocks = Array(STRIPE_COUNT) { Mutex() }

  private data class CacheIdentity(
    val namespace: String,
    val cacheKey: String,
    val appId: String,
    val scene: ImageCacheScene,
  )

  /**
   * 查询仍可复用的上传凭证. 不支持缓存的 [contact] 或数据库异常均按未命中 (返回 null) 处理.
   *
   * 命中时返回的 [TencentOfflineImage] 携带剩余 ttl (秒); 当前发送链路只用到 resourceId,
   * 剩余 ttl 仅用于让对象语义更准确.
   */
  suspend fun find(namespace: String, cacheKey: String, contact: Contact): TencentImage? {
    val identity = contact.cacheIdentity(namespace, cacheKey) ?: return null
    return find(identity)
  }

  /**
   * 将一次新上传得到的凭证写入缓存. 仅接受 resourceId 非空的 [TencentOfflineImage];
   * 其它类型或空凭证 (如 shadow 模式 stub) 不写入. ttl 过短 (<= 安全窗口) 同样不写入.
   *
   * 不支持缓存的 [contact] 为 no-op. 写入异常仅记录日志, 不抛出.
   */
  suspend fun put(namespace: String, cacheKey: String, contact: Contact, image: TencentImage) {
    val identity = contact.cacheIdentity(namespace, cacheKey) ?: return
    put(identity, image)
  }

  /**
   * 命中即返回缓存凭证, 未命中则执行 [upload] 并写入缓存后返回.
   *
   * 适用于"取一张可发送的图"这类简单场景 (plana / rollpig). 若调用方需要在发送失败后做
   * 失效 + 重传, 请改用 [find] + [put] + [invalidateIfMatches] 自行编排 (arona).
   */
  suspend fun getOrUpload(
    contact: Contact,
    namespace: String,
    cacheKey: String,
    upload: suspend () -> TencentImage,
  ): TencentImage {
    val identity = contact.cacheIdentity(namespace, cacheKey) ?: return upload()

    find(identity)?.let { return it }

    return lockFor(identity).withLock {
      // double-check: 可能在等待锁期间已被其它协程上传并写入.
      find(identity)?.let { return@withLock it }
      // upload() 的异常 (含 CancellationException) 直接透传.
      upload().also { put(identity, it) }
    }
  }

  /**
   * 条件失效: 仅当库中该身份的 resourceId 仍等于 [resourceId] 时才删除.
   *
   * 用于发送失败 (凭证可能已失效) 后的回退. 条件删除避免如下竞态: 旧请求取到旧凭证发送失败,
   * 而此时另一请求已上传新凭证并刷新缓存, 普通删除会误删新凭证.
   */
  suspend fun invalidateIfMatches(
    namespace: String,
    cacheKey: String,
    contact: Contact,
    resourceId: String,
  ) {
    val identity = contact.cacheIdentity(namespace, cacheKey) ?: return
    try {
      DatabaseProvider.sqlDbQuerySuspended {
        AronaImageUploadCacheTable.deleteWhere {
          (AronaImageUploadCacheTable.namespace eq identity.namespace) and
            (AronaImageUploadCacheTable.cacheKey eq identity.cacheKey) and
            (AronaImageUploadCacheTable.appId eq identity.appId) and
            (AronaImageUploadCacheTable.scene eq identity.scene.name) and
            (AronaImageUploadCacheTable.resourceId eq resourceId)
        }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      commandLineLogger.warn("图片缓存条件失效失败: ${identity.diagnostic()}", t)
    }
  }

  /**
   * 删除 [nowMillis] 之前已过期的缓存记录, 返回删除行数.
   *
   * 图片缓存规模有限, 单条语句删除即可; 失败仅记录日志.
   */
  suspend fun cleanupExpired(nowMillis: Long = now().toEpochMilliseconds()): Int =
    try {
      DatabaseProvider.sqlDbQuerySuspended {
        AronaImageUploadCacheTable.deleteWhere {
          AronaImageUploadCacheTable.expiresAt less nowMillis
        }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      commandLineLogger.warn("图片缓存过期清理失败", t)
      0
    }

  /**
   * 启动定时清理协程: 启动即清一次, 之后每 6 小时一次. 随 [scope] 取消而结束.
   */
  fun launchCleanup(scope: CoroutineScope): Job =
    scope.launch(CoroutineName("ImageUploadCacheCleanup")) {
      while (isActive) {
        val deleted = cleanupExpired()
        if (deleted > 0) {
          commandLineLogger.info("图片缓存清理: 删除 $deleted 条过期记录")
        }
        delay(CLEANUP_INTERVAL_MILLIS)
      }
    }

  private suspend fun find(identity: CacheIdentity): TencentImage? {
    val nowMillis = now().toEpochMilliseconds()
    val resourceId = try {
      DatabaseProvider.sqlDbQuerySuspended {
        AronaImageUploadCacheTable
          .selectAll()
          .where {
            (AronaImageUploadCacheTable.namespace eq identity.namespace) and
              (AronaImageUploadCacheTable.cacheKey eq identity.cacheKey) and
              (AronaImageUploadCacheTable.appId eq identity.appId) and
              (AronaImageUploadCacheTable.scene eq identity.scene.name) and
              (AronaImageUploadCacheTable.expiresAt greater nowMillis)
          }
          .limit(1)
          .firstOrNull()
          ?.let {
            it[AronaImageUploadCacheTable.resourceId] to it[AronaImageUploadCacheTable.expiresAt]
          }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      commandLineLogger.warn("图片缓存读取失败: ${identity.diagnostic()}", t)
      null
    } ?: return null

    val (id, expiresAt) = resourceId
    if (id.isBlank()) return null

    // 向上取整到秒, 同时防 expiresAt 为 Long.MAX_VALUE 时 +999 溢出为负.
    val remainingMillis = (expiresAt - nowMillis).coerceIn(0L, Long.MAX_VALUE - 999L)
    val remainingSeconds = (remainingMillis + 999L) / 1_000L
    val url = runCatching { getMediaUrlFromMediaInfo(id) }.getOrDefault("")
    return TencentOfflineImage(resourceId = id, resourceUuid = "", ttl = remainingSeconds, url = url)
  }

  private suspend fun put(identity: CacheIdentity, image: TencentImage) {
    if (image !is TencentOfflineImage || image.resourceId.isBlank()) return
    val uploadedAtMillis = now().toEpochMilliseconds()
    val expiresAtMillis = calculateExpiresAt(uploadedAtMillis, image.ttl) ?: return
    try {
      DatabaseProvider.sqlDbQuerySuspended {
        // 不显式传 conflict keys: MariaDB 的 ON DUPLICATE KEY UPDATE 不支持指定冲突列
        // (Exposed 的 MysqlFunctionProvider 在 keys 非空时会直接抛 UnsupportedByDialectException),
        // 冲突目标由数据库现有唯一索引 uq_image_upload_cache_identity 自动决定.
        AronaImageUploadCacheTable.upsert {
          it[namespace] = identity.namespace
          it[cacheKey] = identity.cacheKey
          it[appId] = identity.appId
          it[scene] = identity.scene.name
          it[resourceId] = image.resourceId
          it[createdAt] = uploadedAtMillis
          it[expiresAt] = expiresAtMillis
        }
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      commandLineLogger.warn("图片缓存写入失败: ${identity.diagnostic()}", t)
    }
  }

  /**
   * 从 [Contact] 推导缓存身份. 仅群聊 / 单聊支持; 其它返回 null 表示 bypass.
   * AppID 取稳定的 `bot.unionOpenid`; 为空 (异常态) 时不缓存.
   */
  private fun Contact.cacheIdentity(namespace: String, cacheKey: String): CacheIdentity? {
    val scene = when (this) {
      is Group -> ImageCacheScene.GROUP
      is FriendUser -> ImageCacheScene.C2C
      else -> return null
    }
    val appId = bot.unionOpenid?.takeIf { it.isNotBlank() } ?: return null
    return CacheIdentity(namespace, cacheKey, appId, scene)
  }

  private fun lockFor(identity: CacheIdentity): Mutex =
    uploadLocks[(identity.hashCode() and Int.MAX_VALUE) % uploadLocks.size]

  /**
   * 计算绝对过期时刻 (epoch millis). 返回 null 表示该凭证不值得缓存 (ttl 过短或非法).
   * ttl == 0 (腾讯定义为长期有效) 取 [LONG_TERM_CAP_SECONDS] 上限; ttl < 0 为非法, 不缓存.
   * 全程按 Long 防溢出.
   */
  private fun calculateExpiresAt(uploadedAtMillis: Long, ttlSeconds: Long): Long? {
    val reusableSeconds = when {
      ttlSeconds > 0L -> {
        if (ttlSeconds <= SAFE_WINDOW_SECONDS) return null
        ttlSeconds - SAFE_WINDOW_SECONDS
      }
      ttlSeconds == 0L -> LONG_TERM_CAP_SECONDS
      else -> return null
    }
    if (reusableSeconds > (Long.MAX_VALUE - uploadedAtMillis) / 1_000L) return Long.MAX_VALUE
    return uploadedAtMillis + reusableSeconds * 1_000L
  }

  private fun CacheIdentity.diagnostic() =
    "namespace=$namespace, key=$cacheKey, appId=$appId, scene=$scene"
}
