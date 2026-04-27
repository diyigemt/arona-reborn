package com.diyigemt.arona.communication

import io.ktor.util.logging.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Sprint 3 后续: 锁住 TokenRefresher 的 single-flight + stale-401 抑制 + fetch 异常加固.
//  - 旧实现 (cancel 老 job + launch 新 job + while(true) heartbeat) 在并发 401 下会撞车,
//    且 fetch 抛网络异常时整条刷新链路会静默死亡, 既无 onFatal 也无下次 heartbeat.
//  - 新实现把 (token, version) 作为 AtomicReference<TokenSnapshot> 单原子单元, 写侧 updateAndGet,
//    读侧一次 get 拿到一致快照, 消掉"旧 version + 新 token"的从未真实存在配对带来的 stale 401 误判.
//  - 重试预算 (5s/15s/45s) 通过 retryBackoff 注入, 测试用 short backoff 不真等.
//
// 测试结构注意: refresher 的 launch 用独立 testScope, 不挂 runBlocking. 这样反向验证 (single-flight 失效)
// 时 leaked job 通过 testScope.cancel() 兜底回收, 测试只会 timeout 失败而不会 hang gradle.
class TokenRefresherTest {

  private val testLogger = KtorSimpleLogger("TokenRefresherTest")
  private val fastBackoff = listOf(5L, 5L, 5L)

  // expiresIn 对测试无关紧要 — heartbeat 内部至少 delay 1s, 测试末尾 testScope.cancel() 收尾.
  private fun resp(token: String, expiresIn: Int = 60) =
    TencentBotAuthEndpointResp(accessToken = token, expiresIn = expiresIn)

  private fun runRefresherTest(
    timeoutMs: Long = 5_000,
    block: suspend CoroutineScope.(testScope: CoroutineScope) -> Unit,
  ) {
    val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("TokenRefresherTest"))
    try {
      runBlocking {
        withTimeout(timeoutMs) { block(testScope) }
      }
    } finally {
      testScope.cancel()
    }
  }

  @Test
  fun `首次 trigger 成功更新 snapshot 并通知 onSuccess`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val onSuccessCalled = CompletableDeferred<TencentBotAuthEndpointResp>()
    val onFatalCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        resp("token-1")
      },
      onRefreshSuccess = { onSuccessCalled.complete(it) },
      onFatal = { onFatalCount.incrementAndGet() },
      retryBackoff = fastBackoff,
    )
    refresher.triggerRefresh()
    onSuccessCalled.await()
    assertEquals("token-1", refresher.current().token)
    assertEquals(1L, refresher.current().version, "首次成功 version 从 0 → 1")
    assertEquals(1, attempts.get())
    assertEquals(0, onFatalCount.get(), "成功路径不应触发 onFatal")
    refresher.close()
  }

  @Test
  fun `100 并发 trigger 只调 fetch 一次 single-flight`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val firstFetchStarted = CompletableDeferred<Unit>()
    val firstFetchUnblock = CompletableDeferred<Unit>()
    val onSuccessCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        firstFetchStarted.complete(Unit)
        firstFetchUnblock.await()
        resp("token-1")
      },
      onRefreshSuccess = { onSuccessCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    try {
      // 第一个 trigger 启动 LAZY job, fetch 进入 await 阻塞.
      refresher.triggerRefresh()
      firstFetchStarted.await()
      // in-flight 期间再发 100 个并发 trigger, gate 应全部把它们挡掉.
      coroutineScope {
        (1..100).map {
          async(Dispatchers.Default) { refresher.triggerRefresh() }
        }.awaitAll()
      }
      assertEquals(1, attempts.get(), "in-flight 期间 100 次重复 trigger 不应新开 fetch")
      firstFetchUnblock.complete(Unit)
      // 等 onSuccess 触发
      while (onSuccessCount.get() == 0) delay(5)
      assertEquals(1, attempts.get(), "完成后 fetch 总数仍是 1")
      assertEquals(1, onSuccessCount.get(), "single-flight 应让 onSuccess 只调一次")
    } finally {
      firstFetchUnblock.complete(Unit) // 兜底放行 leaked fetch, 反向场景下避免 hang
      refresher.close()
    }
  }

  @Test
  fun `stale requestVersion 不触发 fetch`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    // 让 fetch 阻塞在 deferred 上, 测试主动放行, 这样 gate 占用时间可控.
    val fetchUnblock = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        val n = attempts.incrementAndGet()
        val d = CompletableDeferred<Unit>().also { fetchUnblock.offer(it) }
        d.await()
        resp("token-$n")
      },
      onRefreshSuccess = { successCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    try {
      // 1. 先发一次 non-stale trigger, 让 first refresh 跑完, version 推进到 1.
      refresher.triggerRefresh()
      while (attempts.get() == 0) delay(5)
      fetchUnblock.poll()!!.complete(Unit)
      while (successCount.get() < 1) delay(5)
      // 等 first job 的 invokeOnCompletion 跑完释放 gate. 用 explicit 探测: 再触发一次 non-stale,
      // 它能进入 gate 并启动新 fetch 说明 gate 空闲了.
      while (true) {
        val before = attempts.get()
        refresher.triggerRefresh()
        delay(20)
        if (attempts.get() > before) break
      }
      // 此时 second fetch 已进入但阻塞在 unblock 上, gate 被 second job 占着. 放行 second.
      fetchUnblock.poll()!!.complete(Unit)
      while (successCount.get() < 2) delay(5)
      // 同样等 second job 释放 gate.
      while (true) {
        val before = attempts.get()
        refresher.triggerRefresh()
        delay(20)
        if (attempts.get() > before) break
      }
      // third fetch 进入了 gate. 放行 third.
      fetchUnblock.poll()!!.complete(Unit)
      while (successCount.get() < 3) delay(5)
      delay(50) // 给 third invokeOnCompletion 时间释放 gate

      // 现在 version=3, gate 应空. 反复用 stale version=0 触发:
      // 正向: 50 次 stale 全被 stale 检查拦下, attempts 不涨.
      // 反向 (stale 检查删了): stale trigger 走 single-flight, 第一发能进 gate → fetch 启动 → attempts++.
      val attemptsBeforeStale = attempts.get()
      repeat(50) { refresher.triggerRefresh(requestVersion = 0L) }
      delay(100)
      assertEquals(attemptsBeforeStale, attempts.get(), "stale requestVersion=0 不应触发新一次 fetch")
    } finally {
      // 兜底放行所有 leaked fetch (反向场景下可能还有 fetch await 着).
      while (fetchUnblock.isNotEmpty()) fetchUnblock.poll()?.complete(Unit)
      refresher.close()
    }
  }

  @Test
  fun `当前 version requestVersion 触发 fetch`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val firstSuccess = CompletableDeferred<Unit>()
    val secondSuccess = CompletableDeferred<Unit>()
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        val n = attempts.incrementAndGet()
        resp("token-$n")
      },
      onRefreshSuccess = {
        if (!firstSuccess.isCompleted) firstSuccess.complete(Unit)
        else secondSuccess.complete(Unit)
      },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      firstSuccess.await()
      val v1 = refresher.current().version
      // 必须等 first refreshJob 完全 complete: onRefreshSuccess 在 body 中段触发,
      // 之后还有 scheduleNextHeartbeat, body 跑完 invokeOnCompletion 才清空 gate.
      // 在这之前 trigger 会被 single-flight gate 当作 in-flight 挡掉. 用 retry trigger 兜底.
      val attemptsBefore = attempts.get()
      while (attempts.get() == attemptsBefore) {
        refresher.triggerRefresh(requestVersion = v1)
        delay(5)
      }
      secondSuccess.await()
      assertEquals(2, attempts.get())
      assertEquals(v1 + 1, refresher.current().version)
      assertEquals("token-2", refresher.current().token)
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `fetch 抛非 CancellationException 被 catch 计入 retry 后续成功`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val onSuccessCalled = CompletableDeferred<Unit>()
    val onFatalCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        val n = attempts.incrementAndGet()
        if (n < 3) throw RuntimeException("flaky $n")
        resp("token-3")
      },
      onRefreshSuccess = { onSuccessCalled.complete(Unit) },
      onFatal = { onFatalCount.incrementAndGet() },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      onSuccessCalled.await()
      assertEquals(3, attempts.get(), "前两次抛异常应被 catch 转 null 计入预算")
      assertEquals("token-3", refresher.current().token)
      assertEquals(0, onFatalCount.get(), "中间失败不应触发 onFatal")
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `所有 fetch 都失败触发 onFatal`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val onFatalCalled = CompletableDeferred<Unit>()
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        null
      },
      onRefreshSuccess = { /* unused */ },
      onFatal = { onFatalCalled.complete(Unit) },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      onFatalCalled.await()
      assertEquals(3, attempts.get(), "走完 3 次预算后 onFatal")
      assertEquals(0L, refresher.current().version, "未成功 snapshot 不应被推进")
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `fetch 抛异常的失败也走完预算到 onFatal`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val onFatalCalled = CompletableDeferred<Unit>()
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        throw RuntimeException("network down")
      },
      onRefreshSuccess = { /* unused */ },
      onFatal = { onFatalCalled.complete(Unit) },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      onFatalCalled.await()
      assertEquals(3, attempts.get(), "异常被 catch 计入预算, 走完 3 次后 onFatal")
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `close 后 trigger 立即 return 不调 fetch`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        resp("never")
      },
      onRefreshSuccess = { /* unused */ },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    refresher.close()
    refresher.triggerRefresh()
    refresher.triggerRefresh(requestVersion = 0L)
    delay(50)
    assertEquals(0, attempts.get(), "close 后任何 trigger 都应被 closed gate 拦下")
  }

  @Test
  fun `close 取消 in-flight refresh job 不更新 snapshot`() = runRefresherTest { testScope ->
    val firstFetchStarted = CompletableDeferred<Unit>()
    val onSuccessCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        firstFetchStarted.complete(Unit)
        // 永远 await, 直到外部 cancel.
        CompletableDeferred<Unit>().await()
        resp("never")
      },
      onRefreshSuccess = { onSuccessCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    refresher.triggerRefresh()
    firstFetchStarted.await()
    refresher.close() // 应该 cancel in-flight fetch
    delay(50)
    assertEquals("", refresher.current().token, "fetch 被 cancel snapshot 不应更新")
    assertEquals(0L, refresher.current().version)
    assertEquals(0, onSuccessCount.get(), "fetch 被 cancel onSuccess 不应触发")
  }

  @Test
  fun `refresh 成功后 heartbeat 到期会再次 trigger`() = runRefresherTest(timeoutMs = 6_000) { testScope ->
    val attempts = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        val n = attempts.incrementAndGet()
        // expiresIn=31 → delayMillis = (31-30) * 1000 = 1000ms, 即 heartbeat 1 秒后到点.
        resp("token-$n", expiresIn = 31)
      },
      onRefreshSuccess = { successCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      while (successCount.get() < 1) delay(5)
      // 等 heartbeat 自动到期 + 触发新 refresh + 完成.
      while (successCount.get() < 2) delay(50)
      assertTrue(attempts.get() >= 2, "heartbeat 到期应再次触发 fetch, attempts=${attempts.get()}")
      assertTrue(refresher.current().version >= 2L, "version 应被 heartbeat 推进")
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `close 取消已 schedule 但未触发的 heartbeat`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        resp("token-1", expiresIn = 31) // delayMillis = 1s
      },
      onRefreshSuccess = { successCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    refresher.triggerRefresh()
    while (successCount.get() < 1) delay(5)
    val attemptsBeforeClose = attempts.get()
    // heartbeat 已 schedule, 1s 后会触发. close 应该把它干掉.
    refresher.close()
    delay(1_500) // 远超 heartbeat 1s 触发点
    assertEquals(attemptsBeforeClose, attempts.get(), "close 后 scheduled heartbeat 不应再触发 fetch")
  }

  @Test
  fun `fetch 抛 CancellationException 不计入 retry 不触发 onFatal`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val onFatalCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        attempts.incrementAndGet()
        throw kotlinx.coroutines.CancellationException("外部取消")
      },
      onRefreshSuccess = { /* unused */ },
      onFatal = { onFatalCount.incrementAndGet() },
      retryBackoff = fastBackoff,
    )
    try {
      refresher.triggerRefresh()
      delay(100)
      assertEquals(1, attempts.get(), "CE 直通, 不计入 retry, 只 fetch 1 次")
      assertEquals(0, onFatalCount.get(), "CE 不应触发 onFatal")
      assertEquals(0L, refresher.current().version, "CE 退出 snapshot 不推进")
    } finally {
      refresher.close()
    }
  }

  @Test
  fun `多次成功 version 单调递增`() = runRefresherTest { testScope ->
    val attempts = AtomicInteger(0)
    val successCount = AtomicInteger(0)
    val refresher = TokenRefresher(
      scope = testScope,
      logger = testLogger,
      fetchAccessToken = {
        val n = attempts.incrementAndGet()
        resp("token-$n")
      },
      onRefreshSuccess = { successCount.incrementAndGet() },
      onFatal = { /* unused */ },
      retryBackoff = fastBackoff,
    )
    try {
      var lastVersion = 0L
      repeat(5) {
        val expected = lastVersion
        // gate 被上一次 refreshJob 占满到 invokeOnCompletion 跑完, 用 retry trigger 兜底.
        while (refresher.current().version == expected) {
          refresher.triggerRefresh()
          delay(5)
        }
        assertTrue(refresher.current().version > expected, "version 应严格单调递增")
        lastVersion = refresher.current().version
      }
      assertEquals(5L, refresher.current().version)
      assertEquals(5, attempts.get())
      assertEquals(5, successCount.get())
    } finally {
      refresher.close()
    }
  }
}
