package com.diyigemt.arona.permission.abac.cache

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.database.permission.PolicyNodeGroupType
import com.diyigemt.arona.database.permission.PolicyRoot
import com.diyigemt.arona.database.permission.PolicyRule
import com.diyigemt.arona.database.permission.PolicyRuleOperator
import com.diyigemt.arona.database.permission.PolicyRuleType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PolicyCompileCacheTest {

  private fun policy(id: String, value: String) = Policy(
    id = id, name = id, effect = PolicyNodeEffect.ALLOW,
    rules = listOf(
      PolicyRoot(
        PolicyNodeGroupType.ALL,
        rule = listOf(PolicyRule(PolicyRuleType.Subject, PolicyRuleOperator.Equal, "id", value))
      )
    )
  )

  @BeforeTest
  fun reset() {
    PolicyCompileCache.invalidateAll()
  }

  @Test
  fun `同一 policy 重复编译命中缓存`() {
    val p = policy("p.1", "u1")
    val first = PolicyCompileCache.getOrCompile(p)
    val second = PolicyCompileCache.getOrCompile(p)
    assertSame(first, second, "相同 (id,hash) 应返回同一个 List 引用")
    assertEquals(1, PolicyCompileCache.sizeForTest())
  }

  @Test
  fun `policy 内容变化 hash 变化 生成新缓存条目`() {
    val v1 = policy("p.same", "u1")
    val v2 = policy("p.same", "u2")
    PolicyCompileCache.getOrCompile(v1)
    PolicyCompileCache.getOrCompile(v2)
    assertEquals(2, PolicyCompileCache.sizeForTest())
  }

  @Test
  fun `invalidateById 清理指定 id 的所有版本`() {
    PolicyCompileCache.getOrCompile(policy("p.a", "u1"))
    PolicyCompileCache.getOrCompile(policy("p.a", "u2"))
    PolicyCompileCache.getOrCompile(policy("p.b", "u1"))
    assertEquals(3, PolicyCompileCache.sizeForTest())

    PolicyCompileCache.invalidateById("p.a")
    assertEquals(1, PolicyCompileCache.sizeForTest(), "p.a 两个版本都应被清掉, p.b 保留")
  }

  @Test
  fun `invalidateAll 清空`() {
    PolicyCompileCache.getOrCompile(policy("p.a", "u1"))
    PolicyCompileCache.getOrCompile(policy("p.b", "u2"))
    PolicyCompileCache.invalidateAll()
    assertEquals(0, PolicyCompileCache.sizeForTest())
  }
}
