package com.diyigemt.arona.permission.abac.compile

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNode
import com.diyigemt.arona.database.permission.PolicyNodeGroupType
import com.diyigemt.arona.database.permission.PolicyRule

/**
 * 将 schema 层 [Policy] 编译为 IR [CompiledPolicy] 列表, 每条 policy.rules[i] 编译为一棵树.
 *
 * 不合并 allow / deny, 上层 [com.diyigemt.arona.permission.abac.eval.PolicyEvaluator] 自己按 effect 分组.
 */
internal object PolicyCompiler {

  fun compile(schema: Policy): List<CompiledPolicy> =
    schema.rules.map { compileNode(it, schema.id) }

  private fun compileNode(node: PolicyNode, policyId: String): CompiledPolicy {
    val children = buildList {
      node.rule?.forEach { add(compileRule(it, policyId)) }
      node.children?.forEach { add(compileNode(it, policyId)) }
    }
    val base: CompiledPolicy = when (node.groupType) {
      PolicyNodeGroupType.ALL, PolicyNodeGroupType.NOT_ALL -> CompiledPolicy.AllOf(policyId, children)
      PolicyNodeGroupType.ANY, PolicyNodeGroupType.NOT_ANY -> CompiledPolicy.AnyOf(policyId, children)
    }
    return when (node.groupType) {
      PolicyNodeGroupType.NOT_ALL, PolicyNodeGroupType.NOT_ANY -> CompiledPolicy.Not(policyId, base)
      PolicyNodeGroupType.ALL, PolicyNodeGroupType.ANY -> base
    }
  }

  private fun compileRule(rule: PolicyRule, policyId: String): CompiledPolicy.Expr =
    CompiledPolicy.Expr(
      policyId = policyId,
      left = AttrRef.of(rule.type, rule.key),
      op = Operator.from(rule.operator),
      right = rule.value,
    )
}
