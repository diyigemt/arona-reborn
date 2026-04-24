package com.diyigemt.arona.permission.abac.compile

import com.diyigemt.arona.database.permission.PolicyRuleOperator
import com.diyigemt.arona.permission.abac.eval.IsChildMatcher

/**
 * 编译后的 10 个操作符. 每个操作符接收 `(left: Any?, right: String)`:
 * - `left` 来自 [AttrRef.read], 可能是 null / String / Number / Boolean / Collection;
 * - `right` 来自 [com.diyigemt.arona.database.permission.PolicyRule.value] (schema 定义为 String).
 *
 * 所有不合法情形 (类型不匹配 / null left / 空集合) 直接返回 false, 不抛异常.
 * Collection 类操作符从 right 按 `,` 切分 (保持与 warden 一致).
 */
internal enum class Operator {
  EQUAL,
  LESS_THAN,
  GREATER_THAN,
  LESS_THAN_EQUAL,
  GREATER_THAN_EQUAL,
  CONTAINS,
  CONTAINS_ALL,
  CONTAINS_ANY,
  IS_IN,
  IS_CHILD;

  fun eval(left: Any?, right: String): Boolean {
    if (left == null) return false
    return when (this) {
      EQUAL -> left == right
      LESS_THAN -> compare(left, right) { it < 0 }
      GREATER_THAN -> compare(left, right) { it > 0 }
      LESS_THAN_EQUAL -> compare(left, right) { it <= 0 }
      GREATER_THAN_EQUAL -> compare(left, right) { it >= 0 }
      CONTAINS -> (left as? Collection<*>)?.contains(right) ?: false
      CONTAINS_ALL -> {
        val lc = left as? Collection<*> ?: return false
        val rc = right.split(",")
        lc.containsAll(rc)
      }
      CONTAINS_ANY -> {
        val lc = left as? Collection<*> ?: return false
        val rc = right.split(",")
        rc.any { lc.contains(it) }
      }
      IS_IN -> right.split(",").contains(left)
      IS_CHILD -> IsChildMatcher.matches(left, right)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private inline fun compare(left: Any, right: String, check: (Int) -> Boolean): Boolean {
    // 与 warden 语义对齐: 仅对可直接 compareTo 的同类型做比较. String vs String 是主流场景.
    // 类型不匹配 (如 Long vs String) 返回 false, 不再抛 BadExpressionException.
    return runCatching {
      val cmp = (left as Comparable<Any>).compareTo(right)
      check(cmp)
    }.getOrDefault(false)
  }

  companion object {
    fun from(op: PolicyRuleOperator): Operator = when (op) {
      PolicyRuleOperator.Equal -> EQUAL
      PolicyRuleOperator.LessThan -> LESS_THAN
      PolicyRuleOperator.GreaterThan -> GREATER_THAN
      PolicyRuleOperator.LessThanEqual -> LESS_THAN_EQUAL
      PolicyRuleOperator.GreaterThanEqual -> GREATER_THAN_EQUAL
      PolicyRuleOperator.Contains -> CONTAINS
      PolicyRuleOperator.ContainsAll -> CONTAINS_ALL
      PolicyRuleOperator.ContainsAny -> CONTAINS_ANY
      PolicyRuleOperator.IsIn -> IS_IN
      PolicyRuleOperator.IsChild -> IS_CHILD
    }
  }
}
