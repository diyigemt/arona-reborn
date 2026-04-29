@file:Suppress("unused")
package com.diyigemt.arona.webui.endpoints.user

import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import io.ktor.server.application.*

@AronaBackendEndpoint("/policy")
object PolicyEndpoint {
  /**
   * 获取用户所有管理的群的规则名称
   */
  @AronaBackendEndpointGet("/policies")
  suspend fun ApplicationCall.userPolicy() {
    // TODO
  }
}
