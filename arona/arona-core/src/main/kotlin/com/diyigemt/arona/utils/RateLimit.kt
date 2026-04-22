package com.diyigemt.arona.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程内按 key (一般是 IP) 的令牌桶限流, 纯 JDK 实现, 无第三方依赖.
 *
 * 取舍:
 *  - P1 需求只是低频接口的爆破抑制, 没必要引入 Bucket4j / Caffeine 等扩面依赖.
 *  - 桶状态用 [AtomicReference] + CAS, 单 key 上 [tryConsume] 是无锁线程安全的.
 *  - 桶映射用 [ConcurrentHashMap] + 容量上限粗清理, 避免长期累积. 不是严格 LRU,
 *    仅用作上限保护; 多实例部署时, 单 IP 的总配额会按实例数线性放大, 后续可换为 Redis Lua.
 *
 * @param capacity      桶容量 (突发上限).
 * @param refillTokens  每个 [refillSeconds] 周期向桶里补充的令牌数.
 * @param refillSeconds 补充周期, 单位秒. > 0.
 */
class IpRateLimiter(
  private val capacity: Long,
  private val refillTokens: Long,
  private val refillSeconds: Long,
  private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
  private val evictBatchSize: Int = DEFAULT_EVICT_BATCH,
  private val clock: () -> Long = System::nanoTime,
) {
  init {
    require(capacity > 0) { "capacity must be > 0" }
    require(refillTokens > 0) { "refillTokens must be > 0" }
    require(refillSeconds > 0) { "refillSeconds must be > 0" }
  }

  private val refillNanos: Long = refillSeconds * 1_000_000_000L
  private val buckets = ConcurrentHashMap<String, Bucket>()

  fun tryConsume(key: String): Boolean {
    if (key.isBlank()) return false
    if (!buckets.containsKey(key) && buckets.size >= maxBuckets) {
      evict()
    }
    return buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), clock()) }.tryConsume()
  }

  private fun evict() {
    val iter = buckets.keys.iterator()
    var removed = 0
    while (iter.hasNext() && buckets.size >= maxBuckets && removed < evictBatchSize) {
      iter.next().also { buckets.remove(it) }
      removed++
    }
  }

  private inner class Bucket(initialTokens: Double, initialNanos: Long) {
    private val state = AtomicReference(State(initialTokens, initialNanos))

    fun tryConsume(): Boolean {
      while (true) {
        val now = clock()
        val current = state.get()
        val elapsed = now - current.lastRefillNanos
        val refilled = if (elapsed > 0) {
          (elapsed.toDouble() / refillNanos) * refillTokens.toDouble()
        } else 0.0
        val available = (current.tokens + refilled).coerceAtMost(capacity.toDouble())
        if (available < 1.0) return false
        val next = State(available - 1.0, now)
        if (state.compareAndSet(current, next)) return true
      }
    }
  }

  private data class State(val tokens: Double, val lastRefillNanos: Long)

  private companion object {
    const val DEFAULT_MAX_BUCKETS = 10_000
    const val DEFAULT_EVICT_BATCH = 1_000
  }
}
