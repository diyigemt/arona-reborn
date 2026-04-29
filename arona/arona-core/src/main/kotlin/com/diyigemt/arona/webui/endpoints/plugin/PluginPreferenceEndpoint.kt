package com.diyigemt.arona.webui.endpoints.plugin

import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.aronaUser
import com.diyigemt.arona.webui.event.auditOrAllow
import com.diyigemt.arona.webui.event.isBlock
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable

@Serializable
data class PluginPreferenceResp(
  val id: String,
  val key: String,
  val value: String,
)

internal data class PreferenceQuery(val id: String, val key: String?)

/**
 * 解析 /plugin/preference 的 query 参数. id 必填且非空; key 可选, 为空字符串视为未传.
 * 抽出纯函数便于单元测试.
 */
internal fun parsePreferenceQuery(rawId: String?, rawKey: String?): PreferenceQuery? {
  val id = rawId?.takeIf { it.isNotBlank() } ?: return null
  return PreferenceQuery(id, rawKey?.takeIf { it.isNotBlank() })
}

@Suppress("unused")
@AronaBackendEndpoint("/plugin/preference")
object PluginPreferenceEndpoint {
  @AronaBackendEndpointGet
  suspend fun ApplicationCall.getPreference() {
    // 此前误用 context.parameters[] (路径参数), 与前端实际传的 query 参数不匹配, 接口形同虚设.
    val query = parsePreferenceQuery(
      request.queryParameters["id"],
      request.queryParameters["key"],
    ) ?: return badRequest()
    if (query.key != null) {
      aronaUser.readPluginConfigOrNull(query.id, query.key)?.also { return success(it) }
      return success()
    }
    aronaUser.readAllConfig(query.id)?.also { return success(it) }
    return success()
  }
  @AronaBackendEndpointPost
  suspend fun ApplicationCall.savePreference() {
    val obj = kotlin.runCatching {
      receive<PluginPreferenceResp>()
    }.onFailure {
      return badRequest()
    }.getOrNull() ?: return badRequest()
    val value = PluginWebuiConfigRecorder.checkDataSafety(obj) ?: return badRequest()
    val audit = auditOrAllow(value)
    if (audit?.isBlock == true) return errorMessage("内容审核失败: ${audit.message}")
    aronaUser.updatePluginConfig(
      obj.id,
      obj.key,
      value
    )
    return success()
  }
}
