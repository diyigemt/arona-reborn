package com.diyigemt.arona.webui.endpoints

import com.diyigemt.arona.database.permission.UserSchema
import com.diyigemt.arona.webui.plugins.AronaAdminToken
import com.diyigemt.arona.webui.plugins.AronaInstanceVersion
import com.diyigemt.arona.webui.plugins.XRealIp
import com.diyigemt.arona.utils.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.*
import io.ktor.util.logging.*
import io.ktor.util.pipeline.*
import java.util.*

val PipelineContext<Unit, ApplicationCall>.version: String?
  get() = context.request.header(HttpHeaders.AronaInstanceVersion)

val PipelineContext<Unit, ApplicationCall>.token: String?
  get() = context.request.header(HttpHeaders.AronaAdminToken)

val PipelineContext<Unit, ApplicationCall>.authorization: String?
  get() = context.request.header(HttpHeaders.Authorization)

val PipelineContext<Unit, ApplicationCall>.ip: String
  get() = context.request.header(HttpHeaders.XRealIp) ?: context.request.origin.remoteAddress

private val ContextUserAttrKey = AttributeKey<UserSchema>("user")

internal var PipelineContext<Unit, ApplicationCall>.aronaUser: UserSchema?
  get() = context.attributes.getOrNull(ContextUserAttrKey)
  set(value) = context.attributes.put(ContextUserAttrKey, value as UserSchema)

@Suppress("unused")
@AronaBackendEndpoint("")
object LoggerInterceptor {
  private val AdminAccessRegexp = Regex("/api/v\\d/admin/.*")

  @AronaBackendRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.accessLogging() {
    val path = context.request.path()
    if (AdminAccessRegexp.matches(path)) {
      // 将admin访问交由adminAccessInterceptor处理
      return
    }
    val query = when (context.request.httpMethod) {
      HttpMethod.Get -> context.request.queryString()
      else -> if (isJsonPost) context.receiveText() else "post blob data"
    }
    val method = context.request.httpMethod.value
    this.authorization?.let { UserSchema.findById(it) }?.also {
      this.aronaUser = it
    }
  }

}

@AronaBackendEndpoint("/admin")
object AdminLoggerInterceptor {
  private val adminLogger = KtorSimpleLogger("admin")
  private val adminToken = aronaConfig.adminToken

  @AronaBackendAdminRouteInterceptor
  suspend fun PipelineContext<Unit, ApplicationCall>.adminAccessLogging() {
    // TODO
    return
    val method = context.request.httpMethod.value
    val query = when (context.request.httpMethod) {
      HttpMethod.Get -> context.request.queryString()
      else -> if (isJsonPost) context.receiveText() else "post blob data"
    }
    val path = context.request.path()
    when (val token = this.token) {
      is String -> {
        if (token != adminToken) {
          adminLogger.warn("failed authorized access: $method: $path with $query by $ip")
          forbidden()
          return finish()
        } else {
          adminLogger.info("admin access: $method: $path with $query by $ip")
        }
      }

      else -> {
        adminLogger.warn("unauthorized admin access: $method: $path with $query by $ip")
        badRequest()
        return finish()
      }
    }
  }
}
