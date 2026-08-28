package com.diyigemt.arona.permission.abac.eval

import com.diyigemt.arona.database.permission.Policy.Companion.BuildInDenyPolicySchema
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseContactAdminPolicy
import com.diyigemt.arona.database.permission.Policy.Companion.createBaseMemberPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

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

}
