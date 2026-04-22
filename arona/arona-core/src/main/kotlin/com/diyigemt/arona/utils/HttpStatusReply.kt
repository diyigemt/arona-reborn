package com.diyigemt.arona.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable

/**
 * 业务码: 与 HTTP 状态码刻意保持数字一致, 但语义独立.
 * HTTP 层负责传输语义 (网关/缓存/重试),
 * 业务层负责前端展示与流程分支, 二者解耦避免把用户输入塞进 HTTP reason phrase.
 */
enum class BusinessCode(val code: Int, val defaultMessage: String) {
  OK(200, "成功"),
  BAD_REQUEST(400, "请求参数错误"),
  UNAUTHORIZED(401, "账户未登录"),
  FORBIDDEN(403, "拒绝访问"),
  NOT_FOUND(404, "请求资源不存在"),
  INTERNAL_ERROR(500, "服务异常, 请联系管理员"),
  BUSINESS_REJECTED(601, "操作失败"),
}

@Serializable
data class ServerResponse<T>(
  val code: Int,
  val message: String,
  val data: T?,
  // 仅 traceId 非 null 时才会序列化 (kotlinx 默认 encodeDefaults=false), 不影响成功响应体积.
  val traceId: String? = null,
) {
  companion object {
    fun <T> success(data: T? = null): ServerResponse<T> = ServerResponse(
      code = BusinessCode.OK.code,
      message = BusinessCode.OK.defaultMessage,
      data = data,
    )

    fun <T> business(
      code: BusinessCode,
      message: String = code.defaultMessage,
      data: T? = null,
      traceId: String? = null,
    ): ServerResponse<T> = ServerResponse(
      code = code.code,
      message = message,
      data = data,
      traceId = traceId,
    )
  }
}

suspend fun PipelineContext<Unit, ApplicationCall>.badRequest() =
  context.respond(HttpStatusCode.BadRequest, ServerResponse.business<Unit>(BusinessCode.BAD_REQUEST))

suspend fun PipelineContext<Unit, ApplicationCall>.unauthorized() =
  context.respond(HttpStatusCode.Unauthorized, ServerResponse.business<Unit>(BusinessCode.UNAUTHORIZED))

suspend fun PipelineContext<Unit, ApplicationCall>.forbidden() =
  context.respond(HttpStatusCode.Forbidden, ServerResponse.business<Unit>(BusinessCode.FORBIDDEN))

suspend fun PipelineContext<Unit, ApplicationCall>.internalServerError() =
  context.respond(HttpStatusCode.InternalServerError, ServerResponse.business<Unit>(BusinessCode.INTERNAL_ERROR))

suspend fun PipelineContext<Unit, ApplicationCall>.errorPermissionDeniedMessage() = errorMessage("权限不足")

/**
 * 业务级拒绝: HTTP 200 + 业务码 601 + 自定义 message.
 * 之前的实现把 message 塞进 HTTP reason phrase, 既不规范, 也存在 CRLF 注入面.
 */
suspend fun PipelineContext<Unit, ApplicationCall>.errorMessage(message: String) =
  context.respond(ServerResponse.business<Unit>(BusinessCode.BUSINESS_REJECTED, message = message))

suspend inline fun <reified T : Any> PipelineContext<Unit, ApplicationCall>.success(data: T) =
  context.respond(ServerResponse.success(data))

suspend fun PipelineContext<Unit, ApplicationCall>.success() =
  context.respond(ServerResponse.success<Unit>(null))
