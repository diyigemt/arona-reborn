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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

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
  )

  /**
   * [checkDataSafety] 的结构化结果. 旧版直接返回 `String?` 把 reject 信息丢给前端的 400, 现在用 sealed
   * result 把 reject message 与 fieldErrors 透传到 endpoint.
   */
  sealed class DataSafetyResult {
    data class Ok(val json: String) : DataSafetyResult()
    data class Err(
      val message: String,
      val fieldErrors: List<FieldError> = emptyList(),
    ) : DataSafetyResult()
  }

  private val map: MutableMap<String, MutableMap<String, ConfigEntry>> = mutableMapOf()

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

  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  fun checkDataSafety(obj: PluginPreferenceResp): DataSafetyResult {
    val entry = getEntry(obj.id, obj.key)
      ?: return DataSafetyResult.Err("配置不存在或未注册: ${obj.id}/${obj.key}")
    val serializer = entry.serializer
    return runCatching {
      val decoded = JsonIgnoreUnknownKeys.decodeFromString(serializer, obj.value) as PluginWebuiConfig
      when (val checkResult = decoded.check()) {
        is PluginConfigCheckResult.PluginConfigCheckReject ->
          return DataSafetyResult.Err(checkResult.message, checkResult.fieldErrors)
        is PluginConfigCheckResult.PluginConfigCheckAccept -> Unit
      }
      DataSafetyResult.Ok(JsonIgnoreUnknownKeys.encodeToString(serializer as KSerializer<PluginWebuiConfig>, decoded))
    }.getOrElse { err ->
      commandLineLogger.error("deserialize ${serializer.descriptor.serialName} failed.")
      commandLineLogger.error(err)
      DataSafetyResult.Err("配置格式错误")
    }
  }

  /** 给一个已注册的 (pluginId, configKey) 生成 schema, 找不到时返回 null. */
  fun generateSchema(id: String, key: String): PluginConfigSchema? {
    val entry = getEntry(id, key) ?: return null
    return SchemaGenerator.generate(
      pluginId = id,
      configKey = key,
      serializer = entry.serializer,
      defaultJson = resolveDefaultJson(entry),
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
    val key = serializer.descriptor.serialName.split(".").last()
    map.getOrPut(namespace) { mutableMapOf() }[key] = ConfigEntry(serializer, defaultProvider)
  }

  private fun getEntry(id: String, key: String): ConfigEntry? = map[id]?.get(key)
}
