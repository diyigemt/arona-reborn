package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import java.util.UUID

@Suppress("unused")
@AronaBackendEndpoint("/user")
object UserEndpoint {
  private fun generateNumber(): String = (1 .. 6).map { "0123456789".random() }.joinToString("")
  @AronaBackendEndpointGet("/login")
  suspend fun PipelineContext<Unit, ApplicationCall>.login() {
    val token = UUID.randomUUID().toString()
    val password = generateNumber()
    redisDbQuery {
      set(password, token)
      expire(password, 600u)
    }
    return success(token to password)
  }
}