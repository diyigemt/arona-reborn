package com.diyigemt.arona.webui.endpoints

import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.database.RedisPrefixKey
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.permission.UserDocument.Companion.findUserDocumentByIdOrNull
import com.diyigemt.arona.utils.*
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.webui.plugins.AronaAdminToken
import com.diyigemt.arona.webui.plugins.AronaInstanceVersion
import com.diyigemt.arona.webui.plugins.XRealIp
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.logging.*
import io.ktor.util.pipeline.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

val PipelineContext<Unit, ApplicationCall>.version: String?
  get() = request.header(HttpHeaders.AronaInstanceVersion)

val PipelineContext<Unit, ApplicationCall>.token: String?
  get() = request.header(HttpHeaders.AronaAdminToken)

val PipelineContext<Unit, ApplicationCall>.authorization: String?
  get() = request.header(HttpHeaders.Authorization)

val PipelineContext<Unit, ApplicationCall>.ip: String
  // 仅在确实部署在反向代理之后时才信任 X-Real-IP, 否则取 socket 远端地址.
  get() = if (aronaConfig.web.behindProxy) {
    request.header(HttpHeaders.XRealIp) ?: request.origin.remoteAddress
  } else {
    request.origin.remoteAddress
  }

val PipelineContext<Unit, ApplicationCall>.request: ApplicationRequest
  get() = context.request

private val ContextUserAttrKey = AttributeKey<UserDocument>("user")

internal var PipelineContext<Unit, ApplicationCall>._aronaUser: UserDocument?
  get() = context.attributes.getOrNull(ContextUserAttrKey)
  set(value) = context.attributes.put(ContextUserAttrKey, value as UserDocument)

internal val PipelineContext<Unit, ApplicationCall>.aronaUser: UserDocument
  get() = context.attributes[ContextUserAttrKey]

/**
 * 解析 RFC 6750 Bearer 令牌.
 * 按 RFC 7235 规定 auth-scheme 不区分大小写, token 自身禁止包含空白字符.
 */
internal fun parseBearer(header: String?): String? {
  val trimmed = header?.trim().orEmpty()
  val schemeEnd = trimmed.indexOf(' ')
  if (schemeEnd <= 0) return null
  val scheme = trimmed.substring(0, schemeEnd)
  if (!scheme.equals("Bearer", ignoreCase = true)) return null
  val token = trimmed.substring(schemeEnd + 1).trim()
  if (token.isEmpty() || token.any { it.isWhitespace() }) return null
  return token
}

/**
 * 常量时间比较 admin token, 避免基于响应耗时的侧信道枚举.
 * expected 为空视为禁用 admin 接口, 任何 token 均拒绝.
 */
internal fun verifyAdminToken(actual: String?, expected: String): Boolean {
  if (expected.isBlank() || actual.isNullOrEmpty()) return false
  return MessageDigest.isEqual(
    actual.toByteArray(StandardCharsets.UTF_8),
    expected.toByteArray(StandardCharsets.UTF_8),
  )
}

@Suppress("unused")
@AronaBackendEndpoint("")
object LoggerInterceptor {
  private val AdminAccessRegexp = Regex("/api/v\\d/admin/.*")

  @AronaBackendRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.accessLogging() {
    val path = context.request.path()
    if (AdminAccessRegexp.matches(path)) {
      // 将 admin 访问交由 adminAccessLogging 处理
      return
    }
    if (path.endsWith("/login") || path.endsWith("/webhook")) {
      return
    }
    val token = parseBearer(this.authorization) ?: run {
      unauthorized()
      return finish()
    }
    val userId = DatabaseProvider.redisDbQuery {
      val key = RedisPrefixKey.buildKey(RedisPrefixKey.WEB_TOKEN, token)
      expire(key, 3600u)
      get(key)
    }
    if (userId == null) {
      unauthorized()
      return finish()
    }
    this._aronaUser = findUserDocumentByIdOrNull(userId)
    if (_aronaUser == null) {
      unauthorized()
      return finish()
    }
  }
}

@AronaBackendEndpoint("/admin")
object AdminLoggerInterceptor {
  private val adminLogger = KtorSimpleLogger("admin")
  private val adminToken = aronaConfig.adminToken

  @AronaBackendAdminRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.adminAccessLogging() {
    val method = context.request.httpMethod.value
    val path = context.request.path()
    val token = this.token
    if (token == null) {
      adminLogger.warn("unauthorized admin access: $method: $path by $ip")
      forbidden()
      return finish()
    }
    if (!verifyAdminToken(token, adminToken)) {
      adminLogger.warn("failed authorized access: $method: $path by $ip")
      forbidden()
      return finish()
    }
    adminLogger.info("admin access: $method: $path by $ip")
  }
}
