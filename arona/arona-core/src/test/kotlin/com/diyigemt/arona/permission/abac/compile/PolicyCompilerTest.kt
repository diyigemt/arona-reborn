package com.diyigemt.arona.permission.abac.compile

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNode
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.database.permission.PolicyNodeGroupType
import com.diyigemt.arona.database.permission.PolicyRoot
import com.diyigemt.arona.database.permission.PolicyRule
import com.diyigemt.arona.database.permission.PolicyRuleOperator
import com.diyigemt.arona.database.permission.PolicyRuleType
import com.diyigemt.arona.permission.abac.AbacRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyCompilerTest {

  private fun req(
    subject: Map<String, Any?> = emptyMap(),
    action: Map<String, Any?> = emptyMap(),
    resource: Map<String, Any?> = emptyMap(),
    environment: Map<String, Any?> = emptyMap(),
  ) = AbacRequest(subject, action, resource, environment)

  @Test
  fun `ALL 叶子规则编译为 AllOf(Expr)`() {
    val policy = Policy(
      id = "p.1",
      name = "p",
      effect = PolicyNodeEffect.ALLOW,
      rules = listOf(
        PolicyRoot(
          groupType = PolicyNodeGroupType.ALL,
          rule = listOf(
            PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1")
          )
        )
      )
    )
    val compiled = PolicyCompiler.compile(policy)
    assertEquals(1, compiled.size)
    val allOf = compiled.first() as CompiledPolicy.AllOf
    assertEquals("p.1", allOf.policyId)
    val expr = allOf.children.first() as CompiledPolicy.Expr
    assertEquals(Operator.EQUAL, expr.op)
    assertEquals(AttrRef.Subject(listOf("id")), expr.left)
    assertEquals("u1", expr.right)
  }

  @Test
  fun `NOT_ALL 编译为 Not(AllOf)`() {
    val policy = Policy(
      "p.not", "p", PolicyNodeEffect.ALLOW,
      rules = listOf(
        PolicyRoot(
          groupType = PolicyNodeGroupType.NOT_ALL,
          rule = listOf(PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
        )
      )
    )
    val compiled = PolicyCompiler.compile(policy)
    val not = compiled.first() as CompiledPolicy.Not
    assertTrue(not.inner is CompiledPolicy.AllOf)
  }

  @Test
  fun `嵌套 children 展开为 CompiledPolicy 树`() {
    val policy = Policy(
      "p.nest", "p", PolicyNodeEffect.ALLOW,
      rules = listOf(
        PolicyRoot(
          groupType = PolicyNodeGroupType.ALL,
          children = listOf(
            PolicyNode(
              groupType = PolicyNodeGroupType.ANY,
              rule = listOf(
                PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"),
                PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u2"),
              )
            )
          )
        )
      )
    )
    val compiled = PolicyCompiler.compile(policy).first() as CompiledPolicy.AllOf
    assertEquals(1, compiled.children.size)
    val anyOf = compiled.children.first() as CompiledPolicy.AnyOf
    assertEquals(2, anyOf.children.size)
    // u1 命中
    assertTrue(compiled.eval(req(subject = mapOf("id" to "u1"))))
    assertTrue(compiled.eval(req(subject = mapOf("id" to "u2"))))
    assertFalse(compiled.eval(req(subject = mapOf("id" to "u3"))))
  }

  @Test
  fun `空 AllOf 求值返回 false`() {
    val policy = Policy("p.empty", "p", PolicyNodeEffect.ALLOW, rules = listOf(PolicyRoot(PolicyNodeGroupType.ALL)))
    val compiled = PolicyCompiler.compile(policy).first()
    assertFalse(compiled.eval(req()))
  }

  @Test
  fun `空 AnyOf 求值返回 false 与 warden 一致`() {
    val policy = Policy("p.empty", "p", PolicyNodeEffect.ALLOW, rules = listOf(PolicyRoot(PolicyNodeGroupType.ANY)))
    val compiled = PolicyCompiler.compile(policy).first()
    assertFalse(compiled.eval(req()))
  }

  @Test
  fun `policyId 从 schema Policy id 向下传递`() {
    val policy = Policy(
      "p.propagate", "p", PolicyNodeEffect.ALLOW,
      rules = listOf(
        PolicyRoot(
          groupType = PolicyNodeGroupType.ALL,
          children = listOf(
            PolicyNode(
              groupType = PolicyNodeGroupType.NOT_ANY,
              rule = listOf(PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", "u1"))
            )
          )
        )
      )
    )
    val compiled = PolicyCompiler.compile(policy).first()
    assertEquals("p.propagate", compiled.policyId)
    val not = (compiled as CompiledPolicy.AllOf).children.first() as CompiledPolicy.Not
    assertEquals("p.propagate", not.policyId)
    assertEquals("p.propagate", not.inner.policyId)
  }
}
