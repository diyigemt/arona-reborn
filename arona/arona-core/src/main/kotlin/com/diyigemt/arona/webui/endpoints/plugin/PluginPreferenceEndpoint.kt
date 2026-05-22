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
import com.diyigemt.arona.webui.pluginconfig.FieldError
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder.DataSafetyResult
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 业务错误响应携带的字段级错误负载, 包装一层让前端 extractFieldErrors 能用对象 schema 识别. */
@Serializable
data class FieldErrorPayload(val fieldErrors: List<FieldError>)

internal fun List<FieldError>.toPayloadOrNull(): FieldErrorPayload? =
  takeIf { it.isNotEmpty() }?.let(::FieldErrorPayload)

/**
 * 插件配置的 wire DTO. `value` 是结构化 JsonObject, 与 Mongo 内 BSON Document 形态一一对应;
 * 不再做"前端 JSON.stringify / 后端 parseToJsonElement"那层多余的字符串转换.
 */
@Serializable
data class PluginPreferenceResp(
  val id: String,
  val key: String,
  val value: JsonObject,
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
      // 走 readPluginConfigRawOrNull 拿原始 JsonObject, 内部会经过 PluginVisibleData 的 alias 回查,
      // 兼容 @PluginConfigId 引入前用 simpleName 存的旧数据.
      aronaUser.readPluginConfigRawOrNull(query.id, query.key)?.also { return success(it) }
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
    val checked = when (val result = PluginWebuiConfigRecorder.checkDataSafety(obj)) {
      is DataSafetyResult.Ok -> result
      is DataSafetyResult.Err ->
        return errorMessage(result.message, result.fieldErrors.toPayloadOrNull())
    }
    val audit = auditOrAllow(checked.json)
    if (audit?.isBlock == true) return errorMessage("内容审核失败: ${audit.message}")
    // 用 canonicalKey 落库: 即使前端用 alias POST 也归一到主 key, 避免新写入污染历史 alias.
    // value 走 checked.element (JsonObject), Mongo driver 编成原生 BSON Document.
    aronaUser.updatePluginConfig(
      obj.id,
      checked.canonicalKey,
      checked.element,
    )
    return success()
  }

  /**
   * 返回某个已注册插件配置的 UI schema (字段类型/标签/默认值/嵌套结构).
   * schema 与作用域 (user/contact/manage-contact) 无关, 一份即可.
   * 未注册时返回 success(null), 与 [getPreference] 的"无内容"语义一致.
   */
  @AronaBackendEndpointGet("/schema", withoutTransaction = true)
  suspend fun ApplicationCall.getPreferenceSchema() {
    val query = parsePreferenceQuery(
      request.queryParameters["id"],
      request.queryParameters["key"],
    ) ?: return badRequest()
    val key = query.key ?: return badRequest()
    val schema = PluginWebuiConfigRecorder.generateSchema(query.id, key) ?: return success()
    return success(schema)
  }
}
