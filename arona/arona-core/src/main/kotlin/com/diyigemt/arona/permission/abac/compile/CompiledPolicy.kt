package com.diyigemt.arona.permission.abac.compile

import com.diyigemt.arona.permission.abac.AbacRequest

/**
 * 编译后的 policy 树. 每个节点持有来源 `policyId`, 便于 Decision 报告具体是哪条策略命中.
 *
 * 不变量:
 * - [AllOf] 在空子集合时返回 `false` (与 warden 一致, 避免静默授权)
 * - [AnyOf] 在空子集合时返回 `false` (与 warden 一致)
 * - [Not] 语义就是布尔取反
 * - [Expr] 调 [Operator.eval] 判布尔, null left 永远 false
 */
internal sealed interface CompiledPolicy {
  val policyId: String

  fun eval(req: AbacRequest): Boolean

  data class AllOf(
    override val policyId: String,
    val children: List<CompiledPolicy>,
  ) : CompiledPolicy {
    override fun eval(req: AbacRequest): Boolean {
      if (children.isEmpty()) return false
      return children.all { it.eval(req) }
    }
  }

  data class AnyOf(
    override val policyId: String,
    val children: List<CompiledPolicy>,
  ) : CompiledPolicy {
    override fun eval(req: AbacRequest): Boolean = children.any { it.eval(req) }
  }

  data class Not(
    override val policyId: String,
    val inner: CompiledPolicy,
  ) : CompiledPolicy {
    override fun eval(req: AbacRequest): Boolean = !inner.eval(req)
  }

  data class Expr(
    override val policyId: String,
    val left: AttrRef,
    val op: Operator,
    val right: String,
  ) : CompiledPolicy {
    override fun eval(req: AbacRequest): Boolean = op.eval(left.read(req), right)
  }
}
