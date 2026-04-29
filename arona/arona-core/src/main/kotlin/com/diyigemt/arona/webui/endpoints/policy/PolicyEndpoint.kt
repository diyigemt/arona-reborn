package com.diyigemt.arona.webui.endpoints.policy

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.permission.abac.AbacRequest
import com.diyigemt.arona.permission.abac.Decision
import com.diyigemt.arona.permission.abac.compile.PolicyCompiler
import com.diyigemt.arona.permission.abac.eval.PolicyEvaluator
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import io.ktor.server.application.*
import io.ktor.server.request.*
import kotlinx.serialization.Serializable

@Suppress("unused")
@AronaBackendEndpoint("/policy")
internal object PolicyEndpoint {

  @AronaBackendEndpointGet("/resources")
  suspend fun ApplicationCall.getResources() {
    success(PermissionService.permissions.keys().toList().map { it.toString() })
  }

  /**
   * 策略在线预览. 前端 webui 去掉本地评估器后改调该接口, 保证前后端语义一致.
   *
   * 入参允许任意数量的 policy 组合 (通常一条), 用任意 subject/action/resource/environment 试跑,
   * 返回命中的 policy.id 和每条规则的求值快照, 用于编辑器高亮。
   */
  @AronaBackendEndpointPost("/preview")
  suspend fun ApplicationCall.previewPolicy() {
    val req = receive<PolicyPreviewReq>()
    val allow = req.policies
      .filter { it.effect == PolicyNodeEffect.ALLOW }
      .flatMap { PolicyCompiler.compile(it) }
    val deny = req.policies
      .filter { it.effect == PolicyNodeEffect.DENY }
      .flatMap { PolicyCompiler.compile(it) }

    // webui 发过来的 attribute 值是 String (含 roles 的逗号分隔 list). 我们把 roles 特殊处理成 List<String>.
    val abacRequest = AbacRequest(
      subject = toAbacMap(req.subject, rolesKey = "roles"),
      action = toAbacMap(req.action),
      resource = toAbacMap(req.resource),
      environment = toAbacMap(req.environment),
    )
    val decision = PolicyEvaluator.evaluate(allow, deny, abacRequest)

    val resp = when (decision) {
      is Decision.Permit -> PolicyPreviewResp(
        decision = "allow",
        hitPolicyId = decision.hitAllowPolicyId,
        reason = decision.reason,
      )
      is Decision.Deny -> PolicyPreviewResp(
        decision = "deny",
        hitPolicyId = decision.hitDenyPolicyId,
        reason = decision.reason,
      )
    }
    success(resp)
  }

  private fun toAbacMap(src: Map<String, String>, rolesKey: String? = null): Map<String, Any?> =
    src.mapValues { (k, v) ->
      if (k == rolesKey) v.split(",").filter { it.isNotBlank() } else v
    }
}

@Serializable
internal data class PolicyPreviewReq(
  val policies: List<Policy>,
  val subject: Map<String, String> = emptyMap(),
  val action: Map<String, String> = emptyMap(),
  val resource: Map<String, String> = emptyMap(),
  val environment: Map<String, String> = emptyMap(),
)

@Serializable
internal data class PolicyPreviewResp(
  val decision: String,
  val hitPolicyId: String?,
  val reason: String,
)
