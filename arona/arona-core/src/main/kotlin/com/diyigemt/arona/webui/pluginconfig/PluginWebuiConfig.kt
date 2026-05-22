package com.diyigemt.arona.webui.pluginconfig

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.database.permission.toMongodbKey
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.error
import com.diyigemt.arona.webui.endpoints.plugin.PluginPreferenceResp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class FieldError(
  val path: String,
  val message: String,
)

sealed class PluginConfigCheckResult {
  abstract val message: String?

  class PluginConfigCheckAccept : PluginConfigCheckResult() {
    override val message: String? = null
  }

  class PluginConfigCheckReject(
    override val message: String,
    val fieldErrors: List<FieldError> = emptyList(),
  ) : PluginConfigCheckResult()
}

@Serializable
abstract class PluginWebuiConfig {
  // 服务端二次校验, 防止前端绕过
  open fun check(): PluginConfigCheckResult = PluginConfigCheckResult.PluginConfigCheckAccept()
}

object PluginWebuiConfigRecorder {

  data class ConfigEntry(
    val serializer: KSerializer<*>,
    val defaultProvider: (() -> PluginWebuiConfig)? = null,
    /** 主 key 的历史别名, 仅供读路径回退使用; 见 [PluginConfigId.aliases]. */
    val aliases: List<String> = emptyList(),
  )

  /** 任意 lookup key 命中后返回的归一化结果: 写路径用 [primaryKey] 落库, 读路径用 [entry.aliases] 续查. */
  private data class MatchedEntry(
    val primaryKey: String,
    val entry: ConfigEntry,
  )

  /**
   * [checkDataSafety] 的结构化结果. 旧版直接返回 `String?` 把 reject 信息丢给前端的 400, 现在用 sealed
   * result 把 reject message / fieldErrors / canonical 写入 key 一起透传到 endpoint.
   */
  sealed class DataSafetyResult {
    /**
     * @param json 归一化后的 JSON 文本, 用于审核与日志
     * @param canonicalKey 主 key. 前端若传 alias, 写路径必须用这个回到主 key, 否则数据会被分裂到旧 alias 下
     * @param element [json] 解析后的 JsonObject, 用于 Mongo `Updates.set` 直接落 BSON Document.
     *   与 [json] 是同一份数据的两种表示, 由 [checkDataSafety] 一次性派生.
     */
    data class Ok(val json: String, val canonicalKey: String, val element: JsonObject) : DataSafetyResult()
    data class Err(
      val message: String,
      val fieldErrors: List<FieldError> = emptyList(),
    ) : DataSafetyResult()
  }

  private val map: MutableMap<String, MutableMap<String, ConfigEntry>> = mutableMapOf()

  /** 注册时禁止出现在 primary key / alias 中的字符. `.` 与 Mongo dot-path 冲突, `$` 是字段操作符前缀. */
  private val FORBIDDEN_KEY_CHARS = listOf('.', '$')

  /**
   * 命令侧 inline 写入路径会用插件传入的任意 `key` 拼 Mongo dot-path; 未走注册期校验也得拦下含
   * `.` / `$` / 空白的 key, 否则等同于把"注册期 fail-fast"绕过.
   * 与 [validateConfigKey] 共用 [FORBIDDEN_KEY_CHARS], 入参检查规则强同源.
   * 由 prepare 层调用, 失败抛 [IllegalArgumentException], prepare 转成 InvalidKey 异常.
   */
  internal fun requireSafeRuntimeKey(key: String) {
    require(key.isNotBlank()) { "plugin config key is blank" }
    val forbidden = FORBIDDEN_KEY_CHARS.firstOrNull { it in key }
    require(forbidden == null) {
      "plugin config key '$key' contains forbidden character '$forbidden'"
    }
  }

  /**
   * 递归校验 JSON 树内部所有字段名是否符合 BSON 字段名约束: 不能含 `.` (Mongo dot-path 分隔符),
   * 也不能以 `$` 开头 (操作符前缀, 老版 Mongo 直接拒收, 新版接受但破坏路径路由).
   *
   * 配置叶子原本是 JSON 文本时, 这些字符只是字面值; 改成原生 BSON 子文档后, kotlinx codec
   * 把 JSON 对象 key 直接当 BSON 字段名写入, 不做转义. 详见预飞测试
   * `PluginConfigCodecRoundTripTest.JsonElementCodec passes dot and dollar field names through`.
   *
   * 命中即抛 [IllegalArgumentException], 由 prepare / endpoint 层各自转 [PluginConfigWriteRejectedException.Kind.InvalidKey]
   * 或 [DataSafetyResult.Err]. [path] 用于 fail 时定位 leaf 位置 (形如 `$.nested.bad.key`).
   */
  internal fun requireSafeBsonLeafKeys(element: JsonElement, path: String = "\$") {
    when (element) {
      is JsonObject -> element.forEach { (key, child) ->
        val childPath = "$path.$key"
        require(!key.contains('.')) {
          "plugin config BSON leaf key '$childPath' contains forbidden character '.'"
        }
        require(!key.startsWith('$')) {
          "plugin config BSON leaf key '$childPath' starts with forbidden character '\$'"
        }
        requireSafeBsonLeafKeys(child, childPath)
      }
      is JsonArray -> element.forEachIndexed { index, child ->
        requireSafeBsonLeafKeys(child, "$path[$index]")
      }
      else -> Unit
    }
  }

  /**
   * 已注册的话返回主 key (alias 入参也能拿到主 key); 未注册返回 null, 写路径自行 fallthrough.
   * 注册不是绝对契约 (反射扫描可能漏到, 也允许插件自由 key 重载), 因此不抛.
   */
  internal fun canonicalKeyOf(pluginId: String, key: String): String? =
    findMatched(pluginId, key)?.primaryKey

  @OptIn(ExperimentalSerializationApi::class)
  fun register(
    owner: AronaPlugin,
    serializer: KSerializer<*>,
    defaultProvider: (() -> PluginWebuiConfig)? = null,
  ) {
    putEntry(owner.description.id.toMongodbKey(), serializer, defaultProvider)
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun register(
    owner: CommandOwner,
    serializer: KSerializer<*>,
    defaultProvider: (() -> PluginWebuiConfig)? = null,
  ) {
    putEntry(owner.permission.id.nameSpace.toMongodbKey(), serializer, defaultProvider)
  }

  /**
   * 给任意 lookup key (可能是主 key 或 alias), 返回同一 entry 下其它可尝试 key.
   * - 传主 key: 返回它的所有 aliases
   * - 传 alias: 返回 [主 key] + 其它 aliases
   * - 找不到 entry: 返回空
   *
   * 仅供读路径在主 key/alias 命中失败时挨个回查; 不触发写回迁移.
   */
  fun siblingKeysFor(pluginId: String, anyKey: String): List<String> {
    val matched = findMatched(pluginId, anyKey) ?: return emptyList()
    return (listOf(matched.primaryKey) + matched.entry.aliases).filter { it != anyKey }
  }

  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  fun checkDataSafety(obj: PluginPreferenceResp): DataSafetyResult {
    val matched = findMatched(obj.id, obj.key)
      ?: return DataSafetyResult.Err("配置不存在或未注册: ${obj.id}/${obj.key}")
    val serializer = matched.entry.serializer
    return runCatching {
      val decoded = JsonIgnoreUnknownKeys.decodeFromString(serializer, obj.value) as PluginWebuiConfig
      when (val checkResult = decoded.check()) {
        is PluginConfigCheckResult.PluginConfigCheckReject ->
          return DataSafetyResult.Err(checkResult.message, checkResult.fieldErrors)
        is PluginConfigCheckResult.PluginConfigCheckAccept -> Unit
      }
      val json = JsonIgnoreUnknownKeys.encodeToString(serializer as KSerializer<PluginWebuiConfig>, decoded)
      // element 与 prepare 层一致: 单一权威源是 json, element 派生. 安全扫描在派生时一并完成,
      // 失败转 Err 让 endpoint 直接 400, 不会污染 Mongo.
      val element = try {
        JsonIgnoreUnknownKeys.parseToJsonElement(json).jsonObject.also { requireSafeBsonLeafKeys(it) }
      } catch (e: IllegalArgumentException) {
        return DataSafetyResult.Err(e.message ?: "配置字段名不安全")
      }
      DataSafetyResult.Ok(
        json = json,
        canonicalKey = matched.primaryKey,
        element = element,
      )
    }.getOrElse { err ->
      commandLineLogger.error("deserialize ${serializer.descriptor.serialName} failed.")
      commandLineLogger.error(err)
      DataSafetyResult.Err("配置格式错误")
    }
  }

  /** 给一个已注册的 (pluginId, configKey) 生成 schema, 找不到时返回 null; schema 内固定吐主 key. */
  fun generateSchema(id: String, key: String): PluginConfigSchema? {
    val matched = findMatched(id, key) ?: return null
    return SchemaGenerator.generate(
      pluginId = id,
      configKey = matched.primaryKey,
      serializer = matched.entry.serializer,
      defaultJson = resolveDefaultJson(matched.entry),
    )
  }

  /**
   * 两段式取默认值:
   * 1. 优先调用插件显式注册的 [ConfigEntry.defaultProvider];
   * 2. 否则尝试用 serializer 反序列化空 JSON `{}`, 让 `@EncodeDefault` 字段填充默认值.
   *
   * 任一段失败都会回到下一步, 全失败返回 null. 失败原因: defaultProvider 抛错 / 配置类含必填非空字段.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  private fun resolveDefaultJson(entry: ConfigEntry): JsonElement? {
    val serializer = entry.serializer as KSerializer<PluginWebuiConfig>
    val provided = entry.defaultProvider?.let { provider ->
      runCatching { provider.invoke() }.getOrNull()
    }
    if (provided != null) {
      return runCatching {
        JsonIgnoreUnknownKeys.encodeToJsonElement(serializer, provided)
      }.getOrNull()
    }
    return runCatching {
      val instance = JsonIgnoreUnknownKeys.decodeFromString(serializer, "{}")
      JsonIgnoreUnknownKeys.encodeToJsonElement(serializer, instance)
    }.getOrNull()
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun putEntry(
    namespace: String,
    serializer: KSerializer<*>,
    defaultProvider: (() -> PluginWebuiConfig)?,
  ) {
    // 主 key 与命令侧 inline 默认 key 走同一函数, 保证两条写入路径强同源.
    val primaryKey = resolveConfigKey(serializer)
    val aliases = resolveConfigAliases(serializer)
    validateConfigKey(primaryKey, namespace, role = "primary key")
    aliases.forEach { alias -> validateConfigKey(alias, namespace, role = "alias") }
    assertNoCollision(namespace, primaryKey, aliases)
    map.getOrPut(namespace) { mutableMapOf() }[primaryKey] = ConfigEntry(serializer, defaultProvider, aliases)
  }

  /**
   * 校验单个 key 是否合法. 启动期 fail-fast, 避免 [PluginConfigId] 把"非法"字符传入 Mongo dot-path
   * 导致写入嵌套字段或被识别为操作符. 当前禁止字符: `.` (路径分隔) / `$` (操作符前缀);
   * 同时拒绝空白 key.
   */
  private fun validateConfigKey(key: String, namespace: String, role: String) {
    require(key.isNotBlank()) {
      "Invalid plugin config key in $namespace ($role): key is blank"
    }
    val forbidden = FORBIDDEN_KEY_CHARS.firstOrNull { it in key }
    require(forbidden == null) {
      "Invalid plugin config key in $namespace ($role): '$key' contains forbidden character '$forbidden'"
    }
  }

  /**
   * 同一 plugin namespace 下, 新 entry 的 primary + aliases 集合不能:
   *   1) 自身有重复
   *   2) 与已注册 entry 的任一 primary / alias 冲突
   * 命中即抛, 避免运行期靠注册顺序决定胜负.
   */
  private fun assertNoCollision(namespace: String, primaryKey: String, aliases: List<String>) {
    val requested = listOf(primaryKey) + aliases
    val duplicates = requested.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
      "Duplicate plugin config key in $namespace: ${duplicates.joinToString()}"
    }
    val inner = map[namespace] ?: return
    val occupied = mutableMapOf<String, String>()
    inner.forEach { (existingPrimary, entry) ->
      occupied[existingPrimary] = existingPrimary
      entry.aliases.forEach { alias -> occupied[alias] = existingPrimary }
    }
    requested.forEach { candidate ->
      val owner = occupied[candidate]
      require(owner == null) {
        "Plugin config key collision in $namespace: '$candidate' already registered by entry '$owner'"
      }
    }
  }

  /**
   * 精确匹配主 key 优先; 命中失败时按 aliases 反查, 用于前端拿到旧 schema key 时也能识别.
   * 写路径调用方需用 [MatchedEntry.primaryKey] 落库, 避免 alias 请求继续污染旧 key.
   */
  private fun findMatched(id: String, key: String): MatchedEntry? {
    val inner = map[id] ?: return null
    inner[key]?.let { return MatchedEntry(key, it) }
    return inner.entries
      .firstOrNull { (_, entry) -> key in entry.aliases }
      ?.let { (primary, entry) -> MatchedEntry(primary, entry) }
  }

}
