package com.diyigemt.arona.webui.pluginconfig

import com.diyigemt.arona.database.permission.toMongodbKey
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.webui.endpoints.plugin.PluginPreferenceResp
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
abstract class PluginWebuiConfig

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
  @Suppress("UNCHECKED_CAST")
  fun checkDataSafety(obj: PluginPreferenceResp): String? {
    val serializer = getSerializer(obj.id, obj.key) ?: return null
    return kotlin.runCatching {
      val decode = JsonIgnoreUnknownKeys.decodeFromString(serializer, obj.value) as Any
      JsonIgnoreUnknownKeys.encodeToString(serializer as KSerializer<Any>, decode)
    }.getOrNull()
  }
  private fun getSerializer(id: String, key: String): KSerializer<*>? {
    return map[id]?.get(key)
  }
}