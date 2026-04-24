package com.diyigemt.arona.permission.abac.eval

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNode
import com.diyigemt.arona.database.permission.PolicyRule
import com.diyigemt.arona.database.permission.PolicyRuleOperator

/**
 * IS_CHILD 操作符的自研实现.
 *
 * 语义规范见 arona-doc/docs/v2/abac/is-child-operator.md. 相较于旧 warden 实现, 唯一的
 * 行为差异是: 右值为空字符串 `""` 时返回 false, 而非抛 NullPointerException.
 *
 * 该 matcher 不负责整棵策略树的求值, 只负责单个 `(left, right)` 值对的布尔判定, 被
 * [com.diyigemt.arona.permission.abac.compile.Operator] 在 Phase 2 调用.
 */
internal object IsChildMatcher {

  /**
   * 规范入口: 判断 [left] IS_CHILD [right].
   *
   * @return true  表示 left 属于 right 描述的层级子空间.
   * @return false 其他情况, 包括类型错误 / 空右值 / 冒号段数不足 / 任一段不匹配.
   */
  fun matches(left: Any?, right: Any?): Boolean {
    if (left !is String || right !is String) return false
    if (right.isEmpty()) return false

    val rightCols = right.split(":")
    val leftCols = left.split(":")
    if (rightCols.size > leftCols.size) return false

    for (i in rightCols.indices) {
      if (!matchColonSection(leftCols[i], rightCols[i])) return false
    }
    return true
  }

  private fun matchColonSection(leftSection: String, rightSection: String): Boolean {
    if (rightSection == "*") return true
    // `*` 只在 ".*" 形式或单独出现时视作通配, 其他位置按字面. 例如 "foo*" / "a*b" 均按字面匹配.
    if (!rightSection.endsWith(".*")) return leftSection == rightSection

    // rightSection 以 ".*" 结尾. 按点号切段, 要求 left 的点号段数严格多于 right 去掉末位 `*` 后的段数,
    // 即 `leftDots.size >= rightDots.size` (其中 rightDots 包含末位 '*'). 这样 "a.*" 下左值至少要有 "a.x".
    val rightDots = rightSection.split(".")
    val leftDots = leftSection.split(".")
    if (leftDots.size < rightDots.size) return false

    // 比较 rightDots[0..size-2] 与 leftDots[0..size-2], 全等才匹配
    val prefixSize = rightDots.size - 1
    for (j in 0 until prefixSize) {
      if (leftDots[j] != rightDots[j]) return false
    }
    return true
  }

  /**
   * 扫描一批 [Policy] 收集所有 `operator = IsChild` 的 `value`, 对每个值用 [matches] 跑一遍
   * [leftSamples], 返回扫描到的权限模式值集合。该入口保留给运维工具 / 在线审计使用。
   */
  fun listIsChildValues(policies: List<Policy>): List<String> {
    val out = mutableListOf<String>()
    policies.forEach { p -> p.rules.forEach { collectFromNode(it, out) } }
    return out
  }

  private fun collectFromNode(node: PolicyNode, sink: MutableList<String>) {
    node.rule?.forEach { if (it.operator == PolicyRuleOperator.IsChild) sink += it.value }
    node.children?.forEach { collectFromNode(it, sink) }
  }
}
