package com.diyigemt.arona.permission.abac.eval

import com.diyigemt.arona.permission.abac.AbacRequest
import com.diyigemt.arona.permission.abac.Decision
import com.diyigemt.arona.permission.abac.compile.CompiledPolicy
import com.diyigemt.arona.utils.commandLineLogger

/**
 * 自研 ABAC evaluator. 与 warden 的 `EnforcementPointDefault` 行为等价:
 * - 先看 deny, 任一命中 -> [Decision.Deny.Kind.DenyMatched]
 * - 再看 allow, 任一命中 -> [Decision.Permit]
 * - 否则 -> [Decision.Deny.Kind.NoAllowMatched]
 *
 * 求值异常 (通常是 policy 配置错误, 如 `LessThan` 两边不可比) 被捕获为 [Decision.Deny.Kind.EvalError],
 * 写错误日志但**不传播**, 保证一条坏规则不会阻塞整个系统.
 */
internal object PolicyEvaluator {

  fun evaluate(
    allow: List<CompiledPolicy>,
    deny: List<CompiledPolicy>,
    req: AbacRequest,
  ): Decision {
    // deny 优先: 任一 deny 命中即 DenyMatched; EvalError 只记日志, 不阻塞其他策略,
    // 否则一条坏 deny 规则会让所有 allow 也无法通过.
    var sawError = false
    for (p in deny) {
      when (safeEval(p, req)) {
        EvalOutcome.TRUE -> return Decision.Deny(
          kind = Decision.Deny.Kind.DenyMatched,
          reason = "deny policy '${p.policyId}' matched",
          hitDenyPolicyId = p.policyId,
        )
        EvalOutcome.ERROR -> sawError = true
        EvalOutcome.FALSE -> Unit
      }
    }
    // allow: 任一 allow 命中即 Permit; EvalError 记日志继续, 让后面的 allow 仍有机会命中.
    for (p in allow) {
      when (safeEval(p, req)) {
        EvalOutcome.TRUE -> return Decision.Permit(
          hitAllowPolicyId = p.policyId,
          reason = "allow policy '${p.policyId}' matched",
        )
        EvalOutcome.ERROR -> sawError = true
        EvalOutcome.FALSE -> Unit
      }
    }
    // 无任何 allow 命中. 如果途中遇到过 EvalError, 用 EvalError 标签以便日志定位配置问题.
    return Decision.Deny(
      kind = if (sawError) Decision.Deny.Kind.EvalError else Decision.Deny.Kind.NoAllowMatched,
      reason = if (sawError)
        "no allow policy matched (some policies failed to evaluate, see logs)"
      else
        "no allow policy matched",
    )
  }

  private fun safeEval(p: CompiledPolicy, req: AbacRequest): EvalOutcome = try {
    if (p.eval(req)) EvalOutcome.TRUE else EvalOutcome.FALSE
  } catch (e: Exception) {
    commandLineLogger.error("abac policy '${p.policyId}' eval error", e)
    EvalOutcome.ERROR
  }

  private enum class EvalOutcome { TRUE, FALSE, ERROR }
}
