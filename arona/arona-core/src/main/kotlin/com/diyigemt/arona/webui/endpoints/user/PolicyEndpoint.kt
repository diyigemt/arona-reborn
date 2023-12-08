@file:Suppress("unused")
package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

@AronaBackendEndpoint("/policy")
object PolicyEndpoint {
  /**
   * 获取用户所有管理的群的规则名称
   */
  @AronaBackendEndpointGet("/policies")
  suspend fun PipelineContext<Unit, ApplicationCall>.userPolicy() {
    // TODO
  }
}
