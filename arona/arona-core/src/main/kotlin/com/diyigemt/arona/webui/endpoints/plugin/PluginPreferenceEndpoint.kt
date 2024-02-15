package com.diyigemt.arona.webui.endpoints.plugin

import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.aronaUser
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.diyigemt.arona.webui.event.isBlock
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
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
    val obj = kotlin.runCatching {
      context.receive<PluginPreferenceResp>()
    }.onFailure {
      return badRequest()
    }.getOrNull() ?: return badRequest()
    val value = PluginWebuiConfigRecorder.checkDataSafety(obj) ?: return badRequest()
    ContentAuditEvent(value).broadcast().also {
      if (it.isBlock) return errorMessage("内容审核失败: ${it.message}")
    }
    aronaUser.updatePluginConfig(
      obj.id,
      obj.key,
      value
    )
    return success()
  }
}
