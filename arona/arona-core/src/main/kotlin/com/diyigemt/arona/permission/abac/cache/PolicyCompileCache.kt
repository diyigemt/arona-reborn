package com.diyigemt.arona.permission.abac.cache

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.permission.abac.compile.CompiledPolicy
import com.diyigemt.arona.permission.abac.compile.PolicyCompiler
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 策略编译缓存. Key 是 `(policy.id, 序列化内容)`: 同一 `id` 下若内容 (rules/effect/name) 变化,
 * 序列化后的 JSON 字符串也会变化, 得到不同 key, 自然失效.
 *
 * 选择 JSON 而非 `Policy.hashCode()`:
 * - 深层嵌套 List + data class 的 hashCode 可能在罕见情况下碰撞 (31 乘数有限空间).
 * - JSON 序列化是 policy 在 Mongo/HTTP 层本身的表达, 等于"字节级"内容指纹, 不会碰撞到业务意义上不同的策略.
 * 代价是编译前要多一次 serialize, 但缓存命中后这一成本可忽略; 且 [Policy] 不大, 序列化 < 1 ms.
 *
 * 内部 Map 用 [ConcurrentHashMap]; computeIfAbsent 对同一 key 的并发编译只触发一次.
 */
internal object PolicyCompileCache {

  private data class Key(val policyId: String, val serialized: String)

  private val cache = ConcurrentHashMap<Key, List<CompiledPolicy>>()
  private val json = Json { encodeDefaults = true }

  fun getOrCompile(policy: Policy): List<CompiledPolicy> {
    val key = Key(policy.id, json.encodeToString(Policy.serializer(), policy))
    return cache.computeIfAbsent(key) { PolicyCompiler.compile(policy) }
  }

  /** 清理指定 policy id 对应的所有缓存项 (不同 contentHash 也一起清). */
  fun invalidateById(policyId: String) {
    cache.keys.removeIf { it.policyId == policyId }
  }

  fun invalidateAll() {
    cache.clear()
  }

  // region 仅测试可见

  internal fun sizeForTest(): Int = cache.size

  // endregion
}
