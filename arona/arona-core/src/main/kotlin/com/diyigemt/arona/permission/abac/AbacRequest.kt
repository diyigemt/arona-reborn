package com.diyigemt.arona.permission.abac

/**
 * 自研 ABAC evaluator 的求值输入.
 *
 * 值类型用 `Any?` 支持 null / String / Collection / Map 等各种 attribute 形态; 下游
 * [com.diyigemt.arona.permission.abac.compile.AttrRef.read] 按路径解析时遇到 null 会自动短路.
 */
internal data class AbacRequest(
  val subject: Map<String, Any?>,
  val action: Map<String, Any?>,
  val resource: Map<String, Any?>,
  val environment: Map<String, Any?>,
)
