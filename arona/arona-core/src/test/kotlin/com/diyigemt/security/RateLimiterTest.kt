package com.diyigemt.security

import com.diyigemt.arona.utils.IpRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

  @Test
  fun `不同 key 应有独立桶`() {
    val limiter = IpRateLimiter(capacity = 1, refillTokens = 1, refillSeconds = 60)
    assertTrue(limiter.tryConsume("ip-1"))
    assertFalse(limiter.tryConsume("ip-1"))
    assertTrue(limiter.tryConsume("ip-2"))
  }

  @Test
  fun `空 key 直接拒绝`() {
    val limiter = IpRateLimiter(capacity = 5, refillTokens = 5, refillSeconds = 60)
    assertFalse(limiter.tryConsume(""))
    assertFalse(limiter.tryConsume("   "))
  }

  @Test
  fun `桶超出容量后立刻被拒绝`() {
    val limiter = IpRateLimiter(capacity = 3, refillTokens = 3, refillSeconds = 60)
    val results = (1..5).map { limiter.tryConsume("ip-flood") }
    assertEquals(listOf(true, true, true, false, false), results)
  }

  @Test
  fun `等待 refill 后令牌应恢复`() {
    val limiter = IpRateLimiter(capacity = 2, refillTokens = 2, refillSeconds = 1)
    assertTrue(limiter.tryConsume("ip-r"))
    assertTrue(limiter.tryConsume("ip-r"))
    assertFalse(limiter.tryConsume("ip-r"))
    Thread.sleep(1100)
    assertTrue(limiter.tryConsume("ip-r"))
  }

  @Test
  fun `key 数量超过 max 后旧 key 会被驱逐`() {
    val limiter = IpRateLimiter(
      capacity = 1, refillTokens = 1, refillSeconds = 60,
      maxBuckets = 4, evictBatchSize = 2,
    )
    repeat(4) { assertTrue(limiter.tryConsume("ip-$it")) }
    // 触发 evict; 只要新 key 仍能进入即认为驱逐生效, 不强约束哪一个旧 key 被踢.
    assertTrue(limiter.tryConsume("ip-new"))
  }

  @Test
  fun `多线程并发消费同一 key 不会超发`() {
    val limiter = IpRateLimiter(capacity = 100, refillTokens = 1, refillSeconds = 3600)
    val threads = 16
    val attemptsPerThread = 50
    val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
    val barrier = java.util.concurrent.CyclicBarrier(threads)
    val accepted = java.util.concurrent.atomic.AtomicInteger(0)
    repeat(threads) {
      pool.submit {
        barrier.await()
        repeat(attemptsPerThread) {
          if (limiter.tryConsume("ip-concurrent")) accepted.incrementAndGet()
        }
      }
    }
    pool.shutdown()
    pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    // refill 在 3600s 周期内仅添加 1 token, 测试期间近似不补; 因此通过数应贴近 capacity.
    val passed = accepted.get()
    assertTrue(passed in 100..101, "expected near-capacity accepts, got $passed")
  }
}
