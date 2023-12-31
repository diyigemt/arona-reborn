package com.diyigemt.arona.webui.endpoints.plugin

import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.aronaUser
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable

@Serializable
data class PluginPreferenceResp(
  val id: String,
  val key: String,
  val value: String,
)

@Suppress("unused")
@AronaBackendEndpoint("/plugin/preference")
object PluginPreferenceEndpoint {
  @AronaBackendEndpointGet
  suspend fun PipelineContext<Unit, ApplicationCall>.getPreference() {
    val pluginId = context.parameters["id"] ?: return badRequest()
    when (val preferenceKey = context.parameters["key"]) {
      is String -> {
        aronaUser.readPluginConfigOrNull(pluginId, preferenceKey)?.also {
          success(it)
        } ?: success()
      }
      else -> {
        aronaUser.readAllConfig(pluginId)?.also {
          success(it)
        } ?: success()
      }
    }
  }
  @AronaBackendEndpointPost
  suspend fun PipelineContext<Unit, ApplicationCall>.savePreference() {
    val obj = kotlin.runCatching { context.receive<PluginPreferenceResp>() }.getOrNull() ?: return badRequest()
    aronaUser.updatePluginConfig(
      obj.id,
      obj.key,
      obj.value
    )
    return success()
  }
}
