package com.diyigemt.arona.database

import org.jetbrains.exposed.v1.core.Table

/**
 * 腾讯群聊 / 单聊图片上传凭证 (file_info) 的持久化缓存表.
 *
 * 缓存的本质是 [com.diyigemt.arona.communication.contact.Contact.uploadImage] 上传后腾讯返回的
 * `resourceId` (即 file_info). 重新发送同一张图时只要复用该 resourceId 即可免去再次上传
 * (见 `TencentGroupMessage` 的构造).
 *
 * 一条缓存的"身份"由四元组唯一确定:
 * - [namespace]: 调用方 (插件) 命名空间, 避免不同插件的逻辑键互相碰撞;
 * - [cacheKey]: 调用方自定义的逻辑资源键 (图片名 / 内容指纹等);
 * - [appId]: 机器人稳定 AppID. 不同 AppID 之间凭证不可复用;
 * - [scene]: 消息场景 (群聊 / 单聊). 腾讯官方明确群聊与单聊凭证不可混用,
 *   但同场景下凭证可跨群 / 跨用户复用.
 *
 * 由 [com.diyigemt.arona.database.DatabaseProvider] 在启动时通过 [AronaDatabase] 扫描并建表.
 */
@AronaDatabase
internal object AronaImageUploadCacheTable : Table(name = "AronaImageUploadCache") {
  // 缓存身份与凭证列统一用 utf8mb4_bin (二进制比较): 默认 *_ci collation 会让唯一索引与
  // invalidateIfMatches 的 resourceId 比较大小写不敏感, 从而把 "Foo" 与 "foo" 这类不同逻辑键
  // (如 rollpig 大小写敏感的 pigId)、或大小写不同的 file_info 误判为同一身份.
  private const val BIN_COLLATE = "utf8mb4_bin"

  /** 代理主键. 仅用于行标识, 不参与缓存命中判定. */
  val id = long("id").autoIncrement()

  /** 调用方命名空间, 例如 "arona" / "plana" / "rollpig". */
  val namespace = varchar("namespace", 32, collate = BIN_COLLATE)

  /** 调用方逻辑资源键. 过长的键应由调用方自行摘要后再传入, 以免超出联合唯一索引长度. */
  val cacheKey = varchar("cache_key", 256, collate = BIN_COLLATE)

  /** 机器人稳定 AppID (`Contact.bot.unionOpenid`). */
  val appId = varchar("app_id", 64, collate = BIN_COLLATE)

  /** 消息场景, 取值为 [com.diyigemt.arona.communication.image.ImageCacheScene] 的枚举名. */
  val scene = varchar("scene", 8, collate = BIN_COLLATE)

  /** 腾讯返回的上传凭证 file_info. 不透明且大小写敏感, 故同样用二进制比较. */
  val resourceId = text("resource_id", collate = BIN_COLLATE)

  /** 写入时刻 (epoch millis), 仅用于诊断. */
  val createdAt = long("created_at")

  /** 绝对过期时刻 (epoch millis). 读取时以此判定是否仍可复用, 清理任务也据此删除. */
  val expiresAt = long("expires_at")

  override val primaryKey = PrimaryKey(id)

  init {
    // 完整缓存身份的唯一约束: upsert 的冲突目标, 也保证同一身份至多一条记录.
    uniqueIndex("uq_image_upload_cache_identity", namespace, cacheKey, appId, scene)
    // 过期清理走该索引扫描.
    index("idx_image_upload_cache_expires_at", false, expiresAt)
  }
}
