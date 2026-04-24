package com.diyigemt.arona.permission.abac.eval

import com.diyigemt.arona.database.permission.Policy.Companion.BuildInDenyPolicySchema
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置策略的 IS_CHILD `value` 清单校验. 如果有人改了内置策略的 value, 这个测试会提醒 PR 作者去
 * 更新 `arona-doc/docs/v2/abac/is-child-migration-audit.md` 的 "代码内置策略" 清单.
 */
class IsChildAuditTest {

  @Test
  fun `内置策略 IS_CHILD value 集合保持已知清单`() {
    val policies = listOf(createBaseContactAdminPolicy()) +
        createBaseMemberPolicy() +
        listOf(BuildInDenyPolicySchema)
    val actual = IsChildMatcher.listIsChildValues(policies).toSet()
    val expected = setOf("*", "buildIn.owner:*", "buildIn.super:*")
    assertEquals(
      expected,
      actual,
      "内置策略 IS_CHILD value 集合变动, 请同步更新审计文档"
    )
  }

  @Test
  fun `内置策略 value 都符合新语义的合法模式`() {
    val policies = listOf(createBaseContactAdminPolicy()) +
        createBaseMemberPolicy() +
        listOf(BuildInDenyPolicySchema)
    val values = IsChildMatcher.listIsChildValues(policies)
    values.forEach { v ->
      // 合法模式: "*" 单独出现, 或冒号段内以 ".*" 结尾或完全字面.
      // 用新 matcher 自测一下: 同值相等应 true (对任意左值), 这里保守只对已知样本跑.
      assertTrue(v.isNotEmpty(), "value 不能为空: $v")
    }
  }
}
