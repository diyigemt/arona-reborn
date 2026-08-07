package com.diyigemt.arona.database.permission

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.database.*
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQuery
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryWithIsolation
import com.diyigemt.arona.database.pluginConfigPath
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.name
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.diyigemt.arona.webui.pluginconfig.preparePluginConfigWrite
import com.diyigemt.arona.webui.pluginconfig.resolveConfigKey
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.bson.Document
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.sql.Connection

private const val BASE_ID_KEY = "BASE_ID"
// 旧逻辑首发号是 1_000_001 ((1_000_000L + 1).toString()); 新实现 inc 后输出, 因此 seed=1_000_000.
private const val BASE_ID_DEFAULT_SEED = 1_000_000L

@Serializable
private data class BaseIdSequence(
  @SerialName("_id")
  val id: String,
  val seq: Long,
)

private object BaseIdSequenceCompanion : DocumentCompanionObject {
  override val documentName = "SystemSequence"
}

private val baseIdLogger = KtorSimpleLogger("BaseIdSequence")

// 仅首个并发请求执行 setOnInsert 播种; 其他请求阻塞在 mutex 直到播种完成, 避免 $inc 抢先创建出 seq=1 的文档.
private val seedMutex = Mutex()
@Volatile
private var baseIdSeeded = false

private suspend fun ensureBaseIdSeeded() {
  if (baseIdSeeded) return
  seedMutex.withLock {
    if (baseIdSeeded) return
    // SQL 高水位由 mirrorBaseIdHighWaterMark 持续镜像; 无法解析或低于底线都视为损坏, 熔断而不是照单播种.
    val legacySeed = sqlDbQuery {
      val raw = SystemPropertiesSchema.findById(BASE_ID_KEY)?.value ?: return@sqlDbQuery BASE_ID_DEFAULT_SEED
      val parsed = raw.toLongOrNull() ?: error("SQL BASE_ID 高水位无法解析: $raw")
      check(parsed >= BASE_ID_DEFAULT_SEED) { "SQL BASE_ID 高水位 $parsed 低于安全底线 $BASE_ID_DEFAULT_SEED" }
      parsed
    }
    // setOnInsert 仅在文档不存在时生效, 已迁移过的实例不会被覆盖回旧值.
    val seeded = BaseIdSequenceCompanion.withCollection<BaseIdSequence, BaseIdSequence?> {
      findOneAndUpdate(
        Filters.eq("_id", BASE_ID_KEY),
        Updates.setOnInsert(BaseIdSequence::seq.name, legacySeed),
        FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
      )
    } ?: error("BASE_ID 序列播种返回 null")
    // 对比 SQL 高水位而非默认底线: 计数器文档仍存在但数值回退 (被旧备份覆盖/篡改) 时也要熔断.
    check(seeded.seq >= legacySeed) {
      "播种后的 BASE_ID 序列 ${seeded.seq} 低于 SQL 高水位 $legacySeed, 计数器疑似回退, 拒绝启用"
    }
    baseIdSeeded = true
  }
}

// 高水位 CAS 的跨事务重试轮数; 竞争者只会把值推得更高, 输一轮通常意味着已被更高值覆盖.
private const val MIRROR_CAS_ATTEMPTS = 3

/**
 * best-effort 把已发出的最大号镜像回 SQL SystemProperties: 计数器与用户档同库, 一起被清掉时
 * (清库事故) 重启后 [ensureBaseIdSeeded] 能从 SQL 播种出真实高水位, 而不是陈旧的初始 seed,
 * 新号才不会与恢复回来的既有档撞 _id.
 *
 * 单调性由"按旧字符串值 CAS"保证: 条件更新只在现值未被并发改动时生效, 落败即重读重试,
 * 因此并发乱序提交不会让高水位倒退. 每轮 CAS 必须是独立事务 —— 同一事务内重读
 * 受快照隔离与 DAO 缓存影响, 只会看到陈值.
 *
 * 镜像失败只告警不阻断发号; 若镜像持续失败后恰好又发生清库, SQL 值仍可能落后, 属已知边界.
 */
private suspend fun mirrorBaseIdHighWaterMark(seq: Long) {
  try {
    repeat(MIRROR_CAS_ATTEMPTS) {
      val done = sqlDbQuery {
        val current = SystemPropertiesSchema.findById(BASE_ID_KEY)?.value
        when {
          // insertIgnore 而非 DAO new: DAO 的 INSERT 迟至事务提交才 flush, 并发撞主键会抛出而终止
          // 整个重试循环; insertIgnore 立即执行, 输给并发创建者时 insertedCount=0, 走下一轮 CAS.
          current == null -> SystemPropertiesTable.insertIgnore {
            it[SystemPropertiesTable.id] = BASE_ID_KEY
            it[SystemPropertiesTable.value] = seq.toString()
          }.insertedCount == 1
          (current.toLongOrNull() ?: Long.MIN_VALUE) >= seq -> true
          else -> SystemPropertiesTable.update({
            (SystemPropertiesTable.id eq BASE_ID_KEY) and (SystemPropertiesTable.value eq current)
          }) { it[value] = seq.toString() } == 1
        }
      }
      if (done) return
    }
    baseIdLogger.warn("BASE_ID 高水位镜像 CAS 连败 $MIRROR_CAS_ATTEMPTS 轮, 放弃本次镜像: seq=$seq")
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (t: Throwable) {
    baseIdLogger.warn("BASE_ID 高水位镜像写入失败: seq=$seq", t)
  }
}

/**
 * Mongo 单文档原子序列, 替代原 SQL `@Synchronized` JVM 锁; 多实例并发下也能正确发号.
 * 已部署实例首次调用时会读 SQL 旧值播种, 避免 ID 回退.
 *
 * 熔断优先于可用性: 计数器文档只允许播种路径创建, 运行中丢失或数值异常时拒绝发号 —
 * 发出低位垃圾号会覆盖/污染 SQL 指针, 把一次数据事故放大成全量指针损坏, 比注册报错严重得多.
 */
internal suspend fun nextBaseId(): String {
  ensureBaseIdSeeded()
  val updated = BaseIdSequenceCompanion.withCollection<BaseIdSequence, BaseIdSequence?> {
    findOneAndUpdate(
      Filters.eq("_id", BASE_ID_KEY),
      Updates.inc(BaseIdSequence::seq.name, 1L),
      FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
    )
  } ?: error("BASE_ID 序列文档缺失, 疑似数据库被清空, 熔断发号")
  check(updated.seq > BASE_ID_DEFAULT_SEED) {
    "发出的 BASE_ID ${updated.seq} 低于安全底线, 计数器疑似被重建/篡改, 熔断发号"
  }
  mirrorBaseIdHighWaterMark(updated.seq)
  return updated.seq.toString()
}

@AronaDatabase
internal object UserTable : IdTable<String>(name = "User") {
  override val id: Column<EntityID<String>> = char("id", 255).entityId() // 藤子给定的id
  val username = text("username").clientDefault { "老师" } // 用户自定义的昵称
  val from = text("from") // 来源的环境id
  val uid = text("uid") // 对应的自己定义的唯一id 和 UserDocument 的 id 关联
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal class UserSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, UserSchema>(UserTable)

  var username by UserTable.username
  var from by UserTable.from
  var uid by UserTable.uid
  val registerTime by UserTable.registerTime
}

fun String.toMongodbKey() = this.replace(".", "·")
fun String.fromMongodbKey() = this.replace("·", ".")

/**
 * 读路径基类: 把"按 (pluginId, key) 取嵌套 BSON 子文档"统一封装.
 * 写路径不在这层抽象, 交给 [PluginUserDocument]/[PluginContactDocument]/[PluginContactMember]
 * 各自约束签名 (member 强制 cid, 不再允许 3-arg 静默吞掉).
 *
 * 存储形态: leaf 类型是 [JsonObject], BSON codec 通过 [com.diyigemt.arona.database.KotlinxJsonElementCodecProvider]
 * 落成原生 BSON Document, 不再走"叶子是 JSON 字符串"的旧形态. 反序列化时 kotlinx 的
 * `decodeFromJsonElement` 直接消费 JsonObject, 没有中间 `parseToJsonElement` 一步.
 */
abstract class PluginVisibleData {
  abstract val config: Map<String, Map<String, JsonObject>>

  @OptIn(InternalSerializationApi::class)
  inline fun <reified T : PluginWebuiConfig> readPluginConfigOrNull(
    plugin: CommandOwner,
    key: String = resolveConfigKey(T::class.serializer()),
  ) = readPluginConfigOrNull(plugin.permission.id.nameSpace.toMongodbKey(), key, T::class.serializer())

  @OptIn(InternalSerializationApi::class)
  inline fun <reified T : PluginWebuiConfig> readPluginConfigOrDefault(
    plugin: CommandOwner,
    default: T,
    key: String = resolveConfigKey(T::class.serializer()),
  ) = readPluginConfigOrDefault(plugin.permission.id.nameSpace.toMongodbKey(), default, key, T::class.serializer())

  @OptIn(InternalSerializationApi::class)
  inline fun <reified T : PluginWebuiConfig> readPluginConfig(
    plugin: CommandOwner,
    key: String = resolveConfigKey(T::class.serializer()),
  ) = readPluginConfig(plugin.permission.id.nameSpace.toMongodbKey(), key, T::class.serializer())

  fun <T : PluginWebuiConfig> readPluginConfigOrNull(pluginId: String, key: String, serializer: KSerializer<T>): T? {
    val raw = lookupRaw(pluginId, key) ?: return null
    return JsonIgnoreUnknownKeys.decodeFromJsonElement(serializer, raw)
  }

  fun <T : PluginWebuiConfig> readPluginConfigOrDefault(
    pluginId: String,
    default: T,
    key: String,
    serializer: KSerializer<T>,
  ): T {
    val raw = lookupRaw(pluginId, key) ?: return default
    return JsonIgnoreUnknownKeys.decodeFromJsonElement(serializer, raw)
  }

  fun <T : PluginWebuiConfig> readPluginConfig(pluginId: String, key: String, serializer: KSerializer<T>): T {
    val raw = lookupRaw(pluginId, key) ?: error("plugin config $pluginId/$key not found")
    return JsonIgnoreUnknownKeys.decodeFromJsonElement(serializer, raw)
  }

  /**
   * 暴露给 wire 层的"取原始 JsonObject"读出口. endpoint 直接把它丢给 Ktor 序列化即可,
   * 等价于"已 decode 出 T 后再 encodeToJsonElement"但少一次 roundtrip.
   *
   * 命令侧不应调用本方法, 应当走 typed [readPluginConfig] / [readPluginConfigOrNull] /
   * [readPluginConfigOrDefault] 以获得编译期类型保证. 本方法只暴露给 HTTP endpoint 透传裸数据.
   */
  fun readPluginConfigRawOrNull(pluginId: String, key: String): JsonObject? =
    lookupRaw(pluginId, key)

  /**
   * 先查传入 key; 没命中时按注册表声明的同组 key (主 key + 其它 aliases) 挨个回查,
   * 兼容传入主或 alias 两种情形. 不在读时把旧数据迁回主 key, 避免读路径触发 Mongo 写.
   */
  private fun lookupRaw(pluginId: String, key: String): JsonObject? {
    val encoded = pluginId.toMongodbKey()
    val inner = config[encoded] ?: return null
    inner[key]?.let { return it }
    for (sibling in PluginWebuiConfigRecorder.siblingKeysFor(encoded, key)) {
      inner[sibling]?.let { return it }
    }
    return null
  }
}

abstract class PluginUserDocument : PluginVisibleData() {
  abstract val id: String
  abstract val unionOpenId: String
  abstract val qq: Long
  abstract val username: String

  /**
   * 写入用户级插件配置. 仅持久化主 key, 不回写 alias 数据.
   * 该 raw 写入不做 check/audit/canonical, 仅供 endpoint 在自己 prepare 之后落库使用;
   * 业务代码请走带类型参数的 inline 重载, 它会经过 [preparePluginConfigWrite] 的完整守卫.
   */
  abstract suspend fun updatePluginConfig(pluginId: String, key: String, value: JsonObject)

  /**
   * 命令侧 typed 写入入口: 经过 [preparePluginConfigWrite] 后再落库.
   * - [audit] 默认 true, 与 endpoint 同款; 写入纯机器派生状态 (计数/开关) 的热路径可显式 false 跳过 3s 审核超时
   * - 失败抛 [com.diyigemt.arona.webui.pluginconfig.PluginConfigWriteRejectedException]
   */
  @OptIn(InternalSerializationApi::class)
  suspend inline fun <reified T : PluginWebuiConfig> updatePluginConfig(
    plugin: CommandOwner,
    value: T,
    key: String = resolveConfigKey(T::class.serializer()),
    audit: Boolean = true,
  ) {
    val ns = plugin.permission.id.nameSpace.toMongodbKey()
    val prepared = preparePluginConfigWrite(ns, key, value, T::class.serializer(), audit = audit)
    updatePluginConfig(ns, prepared.canonicalKey, prepared.element)
  }

  suspend fun updateUsername(name: String) {
    UserDocument.withCollection<MongoUserDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(UserDocument::username.name, name)
      )
    }
  }
}

interface ExposedUserDocument {
  // 稀疏 Map: 仅包含 Mongo 中存在 UserDocument 的 id, 缺失条目不出现在结果中.
  suspend fun querySimplifiedUser(ids: List<String>): Map<String, SimplifiedUserDocument>
}

@Serializable
data class SimplifiedUserDocument(
  val id: String,
  val username: String,
) {
  companion object : ExposedUserDocument by UserDocument.Companion
}

@Serializable
internal data class UserDocument(
  override val id: String, // 自己定义的唯一id
  override val username: String = "Arona用户$id", // 显示在前端的用户名
  override val unionOpenId: String = "", // 藤子定义的唯一id
  override val qq: Long = 0L, // 用户绑定的qq号
  val uid: List<String> = listOf(), // 藤子给定的不同聊天环境下的id
  val contacts: List<String> = listOf(), // 存在的不同的群/频道的id
  val policies: List<Policy> = listOf(), // 用户自定义的规则
  // 主动消息开关状态 (单聊侧). 未按 appId 分区, 边界同 ContactDocument 对应字段.
  val proactiveMessageState: ProactiveMessageState = ProactiveMessageState.UNKNOWN,
  val proactiveMessageStateUpdatedAt: Long = 0L,
  override val config: Map<String, Map<String, JsonObject>> = mapOf(), // 用户自定义的,插件专有的配置项
) : PluginUserDocument() {
  suspend fun updateUserContact(contactId: String) = withCollection<MongoUserDocument, UpdateResult> {
    updateOne(
      filter = idFilter(id),
      update = Updates.addToSet(UserDocument::contacts.name, contactId)
    )
  }

  /**
   * endpoint `/plugin/preference?id=` 的"取一插件全部配置"出口: 直接返回某 namespace 下的
   * `key -> JsonObject` 子树, 由 Ktor 序列化器原生编码为 JSON 对象. 不存在时返回 null,
   * 由 endpoint 自行决定 fallthrough 语义.
   */
  internal fun readAllConfig(pluginId: String): Map<String, JsonObject>? =
    config[pluginId.toMongodbKey()]

  override suspend fun updatePluginConfig(
    pluginId: String,
    key: String,
    value: JsonObject,
  ) {
    withCollection<MongoUserDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(pluginConfigPath(UserDocument::config, pluginId, key), value)
      )
    }
  }

  companion object : DocumentCompanionObject, ExposedUserDocument {
    override val documentName = "User"

    /**
     * 按事件时间条件更新主动消息开关状态; [id] 是 UserDocument 主键 (非平台 openid, 调用方需先经
     * [findUserDocumentByUidOrNull] 映射). filter 语义与 NotMatched/不 upsert/写失败自愈的边界
     * 同 [ContactDocument.Companion.updateProactiveMessageState].
     */
    suspend fun updateProactiveMessageState(
      id: String,
      state: ProactiveMessageState,
      timestamp: Long,
    ): MongoWriteOutcome = withCollection<MongoUserDocument, UpdateResult> {
      updateOne(
        filter = proactiveMessageFreshnessFilter(id, timestamp),
        update = Updates.combine(
          Updates.set(UserDocument::proactiveMessageState.name, state.name),
          Updates.set(UserDocument::proactiveMessageStateUpdatedAt.name, timestamp),
        ),
      )
    }.classify()

    suspend fun findUserDocumentByUidOrNull(uid: String): UserDocument? {
      // 事务内取出指针字符串, 不把 DAO entity 带出事务. READ_COMMITTED 而非脏读:
      // 读到未提交且随后回滚的注册指针, 会被上层当成悬空指针触发错误的重建.
      val pointer = sqlDbQueryWithIsolation(Connection.TRANSACTION_READ_COMMITTED) {
        UserSchema.findById(uid)?.uid
      } ?: return null
      // 空白指针按缺档处理而不是拿去查询: 万一库里存在 _id 为空串的损坏档, 这里会把它当成
      // 正常身份返回; 映射为 null 后统一流向 UserService 的指针校验熔断点.
      if (pointer.isBlank()) return null
      return withCollection<MongoUserDocument, MongoUserDocument?> {
        find(idFilter(pointer)).limit(1).firstOrNull()
      }?.toDomain()
    }

    suspend fun findUserDocumentByIdOrNull(id: String): UserDocument? =
      withCollection<MongoUserDocument, MongoUserDocument?> {
        find(idFilter(id)).limit(1).firstOrNull()
      }?.toDomain()

    override suspend fun querySimplifiedUser(ids: List<String>): Map<String, SimplifiedUserDocument> {
      val filter = Aggregates.match(Filters.`in`("_id", ids))
      val res = withCollection<MongoUserDocument, List<MongoSimplifiedUserDocument>> {
        aggregate<MongoSimplifiedUserDocument>(
          listOf(
            filter,
            Aggregates.project(
              Projections.fields(
                Document("_id", 1),
                Document(UserDocument::username.name, 1)
              )
            )
          )
        ).toList()
      }
      return res.associate { it.id to it.toDomain() }
    }
  }
}

internal fun uidFilter(uid: String) = Filters.elemMatch(UserDocument::uid.name, Filters.eq(uid))
