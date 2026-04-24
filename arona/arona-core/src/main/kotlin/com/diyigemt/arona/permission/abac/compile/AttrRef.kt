package com.diyigemt.arona.permission.abac.compile

import com.diyigemt.arona.database.permission.PolicyRuleType
import com.diyigemt.arona.permission.abac.AbacRequest

/**
 * 编译后的属性引用: `SUBJECT.path` / `RESOURCE.path` / ...
 *
 * 读取语义: 按 [path] 逐层在对应 map 中取值; 中间若有 null 或非 Map 则短路返回 null. 这样
 * "缺属性" 不再抛异常, 统一按 null 流入 [Operator.eval], 结果是 false (deny).
 */
internal sealed interface AttrRef {
  val path: List<String>

  fun read(req: AbacRequest): Any?

  data class Subject(override val path: List<String>) : AttrRef {
    override fun read(req: AbacRequest): Any? = resolve(req.subject, path)
  }

  data class Action(override val path: List<String>) : AttrRef {
    override fun read(req: AbacRequest): Any? = resolve(req.action, path)
  }

  data class Resource(override val path: List<String>) : AttrRef {
    override fun read(req: AbacRequest): Any? = resolve(req.resource, path)
  }

  data class Environment(override val path: List<String>) : AttrRef {
    override fun read(req: AbacRequest): Any? = resolve(req.environment, path)
  }

  companion object {
    /**
     * 构造 AttrRef. arona 的 policy schema 里 `key` 始终是单层字段名 (例如 "id" / "roles"),
     * 这里保留 List<String> 接口以兼容未来可能出现的 "a.b" 形式 ([com.diyigemt.arona.database.permission.PolicyRule.key]
     * 当前不支持点号分层, 但 evaluator 不做限制).
     */
    fun of(type: PolicyRuleType, key: String): AttrRef {
      val path = if (key.isEmpty()) emptyList() else listOf(key)
      return when (type) {
        PolicyRuleType.Subject -> Subject(path)
        PolicyRuleType.Action -> Action(path)
        PolicyRuleType.Resource -> Resource(path)
        PolicyRuleType.Environment -> Environment(path)
      }
    }

    private fun resolve(root: Map<String, Any?>, path: List<String>): Any? {
      if (path.isEmpty()) return null
      var current: Any? = root
      for (segment in path) {
        @Suppress("UNCHECKED_CAST")
        val asMap = current as? Map<String, Any?> ?: return null
        if (!asMap.containsKey(segment)) return null
        current = asMap[segment]
      }
      return current
    }
  }
}
