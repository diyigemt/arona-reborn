package com.diyigemt.arona.database.permission

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.database.*
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQuery
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryReadUncommited
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.bson.Document
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

// 仅首个并发请求执行 setOnInsert 播种; 其他请求阻塞在 mutex 直到播种完成, 避免 $inc 抢先创建出 seq=1 的文档.
private val seedMutex = Mutex()
@Volatile
private var baseIdSeeded = false

private suspend fun ensureBaseIdSeeded() {
  if (baseIdSeeded) return
  seedMutex.withLock {
    if (baseIdSeeded) return
    val legacySeed = sqlDbQuery {
      SystemPropertiesSchema.findById(BASE_ID_KEY)?.value?.toLongOrNull() ?: BASE_ID_DEFAULT_SEED
    }
    // setOnInsert 仅在文档不存在时生效, 已迁移过的实例不会被覆盖回旧值.
    BaseIdSequenceCompanion.withCollection<BaseIdSequence, BaseIdSequence?> {
      findOneAndUpdate(
        Filters.eq("_id", BASE_ID_KEY),
        Updates.setOnInsert(BaseIdSequence::seq.name, legacySeed),
        FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
      )
    }
    baseIdSeeded = true
  }
}

/**
 * Mongo 单文档原子序列, 替代原 SQL `@Synchronized` JVM 锁; 多实例并发下也能正确发号.
 * 已部署实例首次调用时会读 SQL 旧值播种, 避免 ID 回退.
 */
internal suspend fun nextBaseId(): String {
  ensureBaseIdSeeded()
  val updated = BaseIdSequenceCompanion.withCollection<BaseIdSequence, BaseIdSequence?> {
    findOneAndUpdate(
      Filters.eq("_id", BASE_ID_KEY),
      Updates.inc(BaseIdSequence::seq.name, 1L),
      FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER),
    )
  } ?: error("BASE_ID sequence allocation returned null")
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

    suspend fun findUserDocumentByUidOrNull(uid: String): UserDocument? {
      val u = sqlDbQueryReadUncommited {
        UserSchema.findById(uid)
      }
      return if (u == null) {
        null
      } else {
        withCollection<MongoUserDocument, MongoUserDocument?> {
          find(idFilter(u.uid)).limit(1).firstOrNull()
        }?.toDomain()
      }
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
