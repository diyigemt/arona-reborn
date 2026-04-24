package com.diyigemt.arona.webui.endpoints

import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 锁 Sprint 2.2 的关键契约:
//  - Redis SET NX EX 语义——首次 true, 重复 false.
//  - TTL 过期后同 id 可再放行 (不被永久拉黑).
//  - Redis 异常降级 = 放行 (否则故障吃掉事件比重复分派更糟).
//  - 空/null payloadId 跳过幂等不阻塞主路径.
class WebhookIdempotencyStoreTest {

  // 内存幂等 claim: 用 Long clock 参数模拟时间推进, 不依赖真实 Redis.
  private class InMemoryClaim(
    private val now: () -> Long = { 0L },
  ) : IdempotencyClaim {
    // key → 过期时刻 (秒, 同 now 量纲). 过期条目由 `tryClaim` 惰性清理; 测试场景 key 少, 不必建独立回收线程.
    private val state = ConcurrentHashMap<String, Long>()

    override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean {
      val t = now()
      state.entries.removeIf { (_, expiresAt) -> expiresAt <= t }
      if (state.putIfAbsent(key, t + ttlSeconds.toLong()) != null) return false
      return true
    }
  }

  private val logger = KtorSimpleLogger("WebhookIdempotencyStoreTest")

  @Test
  fun `首次 claim 放行 重复 claim 被拦截`() = runBlocking {
    val store = WebhookIdempotencyStore(InMemoryClaim(), logger)
    assertTrue(store.shouldDispatch("evt-1"), "首次应放行")
    assertFalse(store.shouldDispatch("evt-1"), "第二次命中幂等应被拦截")
  }

  @Test
  fun `TTL 过期后同 id 重新放行`() = runBlocking {
    var clock = 0L
    val store = WebhookIdempotencyStore(
      claim = InMemoryClaim { clock },
      logger = logger,
      ttlSeconds = 2u,
    )
    assertTrue(store.shouldDispatch("evt-2"), "首次放行")
    clock = 1L
    assertFalse(store.shouldDispatch("evt-2"), "TTL 未到不应放行")
    clock = 2L
    assertTrue(store.shouldDispatch("evt-2"), "TTL 到点 key 已过期, 应放行")
  }

  @Test
  fun `Redis 异常降级为放行`() = runBlocking {
    val callCount = AtomicInteger(0)
    val store = WebhookIdempotencyStore(
      claim = object : IdempotencyClaim {
        override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean {
          callCount.incrementAndGet()
          throw IllegalStateException("redis connection refused")
        }
      },
      logger = logger,
    )
    assertTrue(store.shouldDispatch("evt-3"), "Redis 异常必须 fail-open")
    assertTrue(store.shouldDispatch("evt-3"), "连续异常每次都放行, 不缓存失败态")
    assertEquals(2, callCount.get(), "每次都真实尝试 claim, 不因异常短路")
  }

  @Test
  fun `空 payloadId 跳过幂等直接放行 不触达 claim`() = runBlocking {
    val callCount = AtomicInteger(0)
    val store = WebhookIdempotencyStore(
      claim = object : IdempotencyClaim {
        override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean {
          callCount.incrementAndGet()
          return false
        }
      },
      logger = logger,
    )
    assertTrue(store.shouldDispatch(null), "null id 放行")
    assertTrue(store.shouldDispatch(""), "空串 id 放行")
    assertTrue(store.shouldDispatch("   "), "纯空白 id 放行")
    assertEquals(0, callCount.get(), "空 id 不应触达底层 claim")
  }

  @Test
  fun `不同 payloadId 互不影响`() = runBlocking {
    val store = WebhookIdempotencyStore(InMemoryClaim(), logger)
    assertTrue(store.shouldDispatch("evt-a"))
    assertTrue(store.shouldDispatch("evt-b"))
    assertFalse(store.shouldDispatch("evt-a"))
    assertFalse(store.shouldDispatch("evt-b"))
  }

  @Test
  fun `CancellationException 透传不被降级吞掉`() {
    val store = WebhookIdempotencyStore(
      claim = object : IdempotencyClaim {
        override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean {
          throw CancellationException("request cancelled by client")
        }
      },
      logger = logger,
    )
    // runCatching 必须显式 rethrow CancellationException, 否则取消语义会被 fail-open 路径吃掉.
    assertFailsWith<CancellationException> {
      runBlocking { store.shouldDispatch("evt-ce") }
    }
  }

  @Test
  fun `重复 claim 不刷新 TTL 窗口`() = runBlocking {
    var clock = 0L
    val store = WebhookIdempotencyStore(
      claim = InMemoryClaim { clock },
      logger = logger,
      ttlSeconds = 3u,
    )
    assertTrue(store.shouldDispatch("evt-ttl-fix"), "首次放行, expiresAt=3")
    clock = 2L
    assertFalse(store.shouldDispatch("evt-ttl-fix"), "TTL 窗口内重复应被拒")
    // 第二次 claim 若错误地刷新 TTL, expiresAt 会被推到 5; 真实实现不应刷新.
    clock = 3L
    assertTrue(store.shouldDispatch("evt-ttl-fix"), "原始 TTL 到期, 同 id 应可重新占用 (而非 expiresAt=5 被延后)")
  }

  @Test
  fun `keyPrefix 与 ttl 构造参数注入生效`() = runBlocking {
    val captured = mutableListOf<Pair<String, UInt>>()
    val spyClaim = object : IdempotencyClaim {
      override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean {
        captured.add(key to ttlSeconds)
        return true
      }
    }
    val store = WebhookIdempotencyStore(
      claim = spyClaim,
      logger = logger,
      keyPrefix = "custom:prefix:",
      ttlSeconds = 42u,
    )
    store.shouldDispatch("evt-x")
    assertEquals(1, captured.size)
    assertEquals("custom:prefix:evt-x", captured[0].first)
    assertEquals(42u, captured[0].second)
  }
}
