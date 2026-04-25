package com.diyigemt.arona.communication.contact

import io.ktor.util.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Sprint 3.5(c) 锁住 GuildImpl.init 的 fetch 重试语义:
//  - 总尝试 4 次 (1 initial + 3 retry), 失败间隔 1s/3s/9s.
//  - 中间失败静默 (callOpenapi 内部已 log), 仅末次失败 warn 一次.
//  - CancellationException 直通, 不被 retry 兜住.
//  - 非 CE 异常 (包括 fetch lambda 抛) 转 Result.failure 进下一轮.
//
// 测试为了不真等 9s, 传入很短的 delays 数组覆盖默认值.
class GuildInitRetryTest {

  private val testLogger = KtorSimpleLogger("GuildInitRetryTest")
  private val fastDelays = longArrayOf(5L, 5L, 5L)

  @Test
  fun `首次成功不重试`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      val result = retryInitFetch<Int>(testLogger, "ok", fastDelays) {
        attempts.incrementAndGet()
        Result.success(42)
      }
      assertEquals(42, result.getOrNull())
      assertEquals(1, attempts.get(), "首次成功后不应再重试")
    }
  }

  @Test
  fun `第 N 次成功只重试到那为止`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      val result = retryInitFetch<String>(testLogger, "transient", fastDelays) {
        val n = attempts.incrementAndGet()
        if (n < 3) Result.failure(RuntimeException("flaky $n"))
        else Result.success("ok")
      }
      assertEquals("ok", result.getOrNull())
      assertEquals(3, attempts.get(), "第 3 次成功后不应再有 attempt")
    }
  }

  @Test
  fun `所有 4 次都失败 返回最后一次的 failure`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      val result = retryInitFetch<Int>(testLogger, "always-fail", fastDelays) {
        val n = attempts.incrementAndGet()
        Result.failure(RuntimeException("fail $n"))
      }
      assertTrue(result.isFailure, "全失败 retry 仍然返回 Result.failure")
      assertEquals(4, attempts.get(), "1 initial + 3 retry 共 4 次 attempt")
      val cause = result.exceptionOrNull()
      assertTrue(cause is RuntimeException)
      assertEquals("fail 4", cause.message, "返回的应该是末次失败")
    }
  }

  @Test
  fun `fetch lambda 直接抛非 CancellationException 也走 retry`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      val result = retryInitFetch<Int>(testLogger, "throwing-fetch", fastDelays) {
        val n = attempts.incrementAndGet()
        if (n < 4) throw RuntimeException("boom $n")
        Result.success(99)
      }
      assertEquals(99, result.getOrNull(), "throw 异常被 catch 转 failure 不应中断 retry 循环")
      assertEquals(4, attempts.get())
    }
  }

  @Test
  fun `CancellationException 必须直通 不被 retry 兜住`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      // 用 coroutineScope { async { retryInitFetch ... } }, 外层 cancel 时 retry 应该立即抛 CE 出来.
      assertFailsWith<CancellationException> {
        coroutineScope {
          val deferred = async {
            retryInitFetch<Int>(testLogger, "cancellable", fastDelays) {
              attempts.incrementAndGet()
              throw CancellationException("外部取消")
            }
          }
          deferred.await()
        }
      }
      assertEquals(1, attempts.get(), "CE 必须立刻终止 retry, 不应再次尝试")
    }
  }

  @Test
  fun `外部 scope cancel 时 retry 在 delay 阶段被打断`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      // delay() 是 cancellable 的, 外部 cancel 后 retry 不应再继续.
      val result = runCatching {
        coroutineScope {
          val job = async {
            retryInitFetch<Int>(testLogger, "scope-cancelled", longArrayOf(10_000L, 10_000L, 10_000L)) {
              attempts.incrementAndGet()
              Result.failure(RuntimeException("force into delay"))
            }
          }
          // 让第一轮 fetch 跑完进入 delay(10s), 然后 cancel.
          delay(50)
          job.cancel()
          job.await()
        }
      }
      assertTrue(result.isFailure, "外部 cancel 应让 await 抛 CE")
      assertTrue(
        result.exceptionOrNull() is CancellationException,
        "delay 阶段被 cancel 必须抛 CE 而不是变成普通 Result.failure",
      )
    }
  }

  @Test
  fun `delays 为空数组时只 attempt 一次`() {
    val attempts = AtomicInteger(0)
    runBlocking {
      val result = retryInitFetch<Int>(testLogger, "no-retry", longArrayOf()) {
        attempts.incrementAndGet()
        Result.failure(RuntimeException("once"))
      }
      assertTrue(result.isFailure)
      assertEquals(1, attempts.get(), "delays 为空时只跑初始 1 次, 不重试")
    }
  }
}
