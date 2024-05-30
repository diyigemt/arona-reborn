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

sealed class PluginConfigCheckResult {
  abstract val message: String?
  class PluginConfigCheckAccept: PluginConfigCheckResult() {
    override val message = null
  }
  class PluginConfigCheckReject(
    override val message: String
  ) : PluginConfigCheckResult()
}

@Serializable
abstract class PluginWebuiConfig {
  // 更新用户提交的信息, 防止注入
  open fun check(): PluginConfigCheckResult = PluginConfigCheckResult.PluginConfigCheckAccept()
}

object PluginWebuiConfigRecorder {
  private val map : MutableMap<String, MutableMap<String, KSerializer<*>>> = mutableMapOf()
  @OptIn(ExperimentalSerializationApi::class)
  fun register(owner: AronaPlugin, serializer: KSerializer<*>) {
    val key = serializer.descriptor.serialName.split(".").last()
    map.getOrPut(owner.description.id.toMongodbKey()) {
      mutableMapOf(key to serializer)
    }.also {
      it[key] = serializer
    }
  }
  @OptIn(ExperimentalSerializationApi::class)
  fun register(owner: CommandOwner, serializer: KSerializer<*>) {
    val key = serializer.descriptor.serialName.split(".").last()
    map.getOrPut(owner.permission.id.nameSpace.toMongodbKey()) {
      mutableMapOf(key to serializer)
    }.also {
      it[key] = serializer
    }
  }
  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  fun checkDataSafety(obj: PluginPreferenceResp): String? {
    val serializer = getSerializer(obj.id, obj.key) ?: return null
    return kotlin.runCatching {
      val decode = JsonIgnoreUnknownKeys.decodeFromString(serializer, obj.value) as PluginWebuiConfig
      when (decode.check()) {
        is PluginConfigCheckResult.PluginConfigCheckReject -> return null
        else -> {}
      }
      JsonIgnoreUnknownKeys.encodeToString(serializer as KSerializer<PluginWebuiConfig>, decode)
    }.onFailure {
      commandLineLogger.error("deserialize ${serializer.descriptor.serialName} failed.")
      commandLineLogger.error(it)
    }.getOrNull()
  }
  private fun getSerializer(id: String, key: String): KSerializer<*>? {
    return map[id]?.get(key)
  }
}
