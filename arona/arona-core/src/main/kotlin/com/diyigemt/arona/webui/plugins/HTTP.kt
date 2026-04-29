package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.commandLineLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.request.*

val HttpHeaders.AronaInstanceVersion: String
  get() = "Version"

val HttpHeaders.AronaAdminToken: String
  get() = "Token"

val HttpHeaders.XRealIp: String
  get() = "X-Real-IP"

suspend inline fun <reified T> ApplicationCall.receiveJson(): T {
  return JsonIgnoreUnknownKeys.decodeFromString(receiveText())
}

suspend inline fun <reified T> ApplicationCall.receiveJsonOrNull(): T? {
  return runCatching {
    receiveJson<T>()
  }.getOrNull()
}

fun Application.configureHTTP() {
  // 反向代理头解析仅在显式声明部署在代理之后时启用, 避免无代理环境下被任意伪造 X-Forwarded-* / X-Real-IP.
  if (aronaConfig.web.behindProxy) {
    install(ForwardedHeaders)
    install(XForwardedHeaders)
  }
  install(CORS) {
    allowMethod(HttpMethod.Options)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Delete)
    allowHeader(HttpHeaders.Host)
    allowHeader(HttpHeaders.Origin)
    allowHeader(HttpHeaders.Accept)
    allowHeader(HttpHeaders.Referrer)
    allowHeader(HttpHeaders.UserAgent)
    allowHeader(HttpHeaders.Connection)
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.AcceptEncoding)
    allowHeader(HttpHeaders.AcceptLanguage)
    allowHeader(HttpHeaders.AronaInstanceVersion)
    allowHeader(HttpHeaders.AronaAdminToken)
    allowHeader(HttpHeaders.XRealIp)
    allowNonSimpleContentTypes = true
    val allowedOrigins = aronaConfig.web.allowedOrigins
    if (allowedOrigins.isEmpty()) {
      commandLineLogger.warn("CORS allowedOrigins 未配置, 临时使用 anyHost. 生产环境必须显式配置 web.allowedOrigins.")
      anyHost()
    } else {
      allowedOrigins.forEach { allowHost(it, schemes = listOf("http", "https")) }
    }
  }
}
