package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.utils.BusinessCode
import com.diyigemt.arona.utils.ServerResponse
import com.diyigemt.arona.utils.commandLineLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.respond
import java.util.UUID

fun Application.configureErrorHandler() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      // 短 traceId 给前端定位用, 完整异常仍写日志便于排查; 不再把 cause.message 直接吐回客户端.
      val traceId = UUID.randomUUID().toString().replace("-", "").take(12)
      commandLineLogger.error("[traceId=$traceId] unhandled server exception", cause)
      call.respond(
        HttpStatusCode.InternalServerError,
        ServerResponse.business<Unit>(
          code = BusinessCode.INTERNAL_ERROR,
          message = "服务异常, 请联系管理员",
          traceId = traceId,
        ),
      )
    }
  }
}
