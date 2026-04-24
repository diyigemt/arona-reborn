package com.diyigemt.arona.permission.abac.eval

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.database.permission.PolicyNodeGroupType
import com.diyigemt.arona.database.permission.PolicyRoot
import com.diyigemt.arona.database.permission.PolicyRule
import com.diyigemt.arona.database.permission.PolicyRuleOperator
import com.diyigemt.arona.database.permission.PolicyRuleType
import com.diyigemt.arona.permission.abac.AbacRequest
import com.diyigemt.arona.permission.abac.Decision
import com.diyigemt.arona.permission.abac.compile.PolicyCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyEvaluatorTest {

  private fun allowPolicy(id: String, rule: PolicyRule) = Policy(
    id = id, name = id, effect = PolicyNodeEffect.ALLOW,
    rules = listOf(PolicyRoot(PolicyNodeGroupType.ALL, rule = listOf(rule)))
  )

  private fun denyPolicy(id: String, rule: PolicyRule) = Policy(
    id = id, name = id, effect = PolicyNodeEffect.DENY,
    rules = listOf(PolicyRoot(PolicyNodeGroupType.ALL, rule = listOf(rule)))
  )

  private val req = AbacRequest(
    subject = mapOf("id" to "u1"),
    action = mapOf("type" to "effect"),
    resource = mapOf("id" to "r.a"),
    environment = emptyMap(),
  )

  @Test
  fun `allow 命中 deny 未命中 返回 Permit`() {
    val allow = PolicyCompiler.compile(
      allowPolicy("a.1", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
    )
    val deny = PolicyCompiler.compile(
      denyPolicy("d.1", PolicyRule(PolicyRuleType.Resource, PolicyRuleOperator.Equal, "id", "r.blocked"))
    )
    val decision = PolicyEvaluator.evaluate(allow, deny, req)
    val permit = assertIs<Decision.Permit>(decision)
    assertEquals("a.1", permit.hitAllowPolicyId)
  }

  @Test
  fun `allow 命中 deny 也命中 返回 Deny_DenyMatched`() {
    val allow = PolicyCompiler.compile(
      allowPolicy("a.1", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
    )
    val deny = PolicyCompiler.compile(
      denyPolicy("d.1", PolicyRule(PolicyRuleType.Resource, PolicyRuleOperator.Equal, "id", "r.a"))
    )
    val decision = PolicyEvaluator.evaluate(allow, deny, req)
    val denied = assertIs<Decision.Deny>(decision)
    assertEquals(Decision.Deny.Kind.DenyMatched, denied.kind)
    assertEquals("d.1", denied.hitDenyPolicyId)
  }

  @Test
  fun `allow 全未命中 返回 Deny_NoAllowMatched`() {
    val allow = PolicyCompiler.compile(
      allowPolicy("a.1", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u999"))
    )
    val decision = PolicyEvaluator.evaluate(allow, emptyList(), req)
    val denied = assertIs<Decision.Deny>(decision)
    assertEquals(Decision.Deny.Kind.NoAllowMatched, denied.kind)
  }

  @Test
  fun `空 allow 空 deny 返回 Deny_NoAllowMatched`() {
    val decision = PolicyEvaluator.evaluate(emptyList(), emptyList(), req)
    val denied = assertIs<Decision.Deny>(decision)
    assertEquals(Decision.Deny.Kind.NoAllowMatched, denied.kind)
  }

  @Test
  fun `多条 allow 第二条命中返回 Permit 并报告正确 id`() {
    val allow = PolicyCompiler.compile(
      allowPolicy("a.first", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u0"))
    ) + PolicyCompiler.compile(
      allowPolicy("a.second", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
    )
    val decision = PolicyEvaluator.evaluate(allow, emptyList(), req)
    val permit = assertIs<Decision.Permit>(decision)
    assertEquals("a.second", permit.hitAllowPolicyId)
  }

  @Test
  fun `缺属性不抛异常 按 deny 兜底`() {
    val allow = PolicyCompiler.compile(
      allowPolicy("a.1", PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
    )
    val emptyReq = AbacRequest(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    val decision = PolicyEvaluator.evaluate(allow, emptyList(), emptyReq)
    assertTrue(decision is Decision.Deny)
  }
}
