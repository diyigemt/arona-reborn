package com.diyigemt.arona.webui.endpoints.policy

import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

@Suppress("unused")
@AronaBackendEndpoint("/policy")
internal object PolicyEndpoint {
  @AronaBackendEndpointGet("/resources")
  suspend fun PipelineContext<Unit, ApplicationCall>.getResources() {
    success(PermissionService.permissions.keys().toList().map { it.toString() })
  }
}
