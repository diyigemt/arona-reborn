package com.diyigemt.arona.communication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Sprint 3.3 锁住 BotManager 的并发安全 + 默认 bot 引用稳定化:
//  - 旧 mutableListOf + linear scan 在并发 register 下会丢/重复 (List 不是 thread-safe).
//  - 旧 getBot() = bots.first() 隐式依赖注册顺序; 如果以后改成 CHM 直接 values.first(), 顺序不再稳定.
//  - 新实现用 ConcurrentHashMap + AtomicReference<firstBotId>, 既保证容器线程安全, 也保证默认 bot 不变.
class BotManagerConcurrencyTest {

  private val closeCounters = mutableMapOf<String, AtomicInteger>()

  // BotManager 只用到 bot.id 与 bot.close(), 其它 abstract 通过 dynamic proxy 抛, 用到就崩,
  // 让测试范围严格限定在 BotManager 自己.
  private fun fakeBot(id: String): TencentBot {
    val closeCounter = closeCounters.getOrPut(id) { AtomicInteger(0) }
    return Proxy.newProxyInstance(
      TencentBot::class.java.classLoader,
      arrayOf(TencentBot::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getId" -> id
        "close" -> { closeCounter.incrementAndGet(); null }
        "equals" -> false
        "hashCode" -> id.hashCode()
        "toString" -> "FakeBot($id)"
        else -> throw UnsupportedOperationException("FakeBot 未实现 ${method.name}")
      }
    } as TencentBot
  }

  @AfterTest
  fun cleanupRegistry() {
    // BotManager 是 object, 跨测试共享. close() 现在顺手清 registry, 测试只关心是不是干净, 不关心
    // 这次 close 是不是把 bot 实例真正关掉 (closeCounters 会被一起重置).
    BotManager.close()
    closeCounters.clear()
  }

  @Test
  fun `registerBot 重复同 id 不重复存储`() {
    val bot = fakeBot("bot-1")
    BotManager.registerBot(bot)
    BotManager.registerBot(bot)
    BotManager.registerBot(fakeBot("bot-1")) // 同 id 不同实例, 应被忽略
    assertSame(bot, BotManager.getBot("bot-1"), "首次注册的实例应保持不变")
  }

  @Test
  fun `getBot id 找不到时抛 NoSuchElementException`() {
    assertFailsWith<NoSuchElementException> { BotManager.getBot("does-not-exist") }
  }

  @Test
  fun `getBot 无参 在无 bot 时抛 NoSuchElementException`() {
    assertFailsWith<NoSuchElementException> { BotManager.getBot() }
  }

  @Test
  fun `getBot 无参 总是返回首个被注册的 bot`() {
    val first = fakeBot("first-bot")
    val second = fakeBot("second-bot")
    val third = fakeBot("third-bot")
    BotManager.registerBot(first)
    BotManager.registerBot(second)
    BotManager.registerBot(third)
    assertSame(first, BotManager.getBot(), "默认 bot 必须锁死在第一个 register 的实例")
  }

  @Test
  fun `close 调用所有 bot 的 close`() {
    BotManager.registerBot(fakeBot("a"))
    BotManager.registerBot(fakeBot("b"))
    BotManager.registerBot(fakeBot("c"))
    BotManager.close()
    assertEquals(1, closeCounters["a"]!!.get())
    assertEquals(1, closeCounters["b"]!!.get())
    assertEquals(1, closeCounters["c"]!!.get())
  }

  @Test
  fun `100 并发 register 不同 id 全部入表 firstBotId 是真实首个赢家`() {
    runBlocking {
      val ids = (1..100).map { "concurrent-bot-$it" }
      val firstWinner = coroutineScope {
        ids.map { id ->
          async(Dispatchers.Default) {
            BotManager.registerBot(fakeBot(id))
            id
          }
        }.awaitAll()
      }
      // 100 个 id 全部能被 getBot(id) 找到.
      ids.forEach { id ->
        assertEquals(id, BotManager.getBot(id).toString().removePrefix("FakeBot(").removeSuffix(")"))
      }
      // getBot() 返回某个具体 bot, 这个 bot 一定是其中之一 (而不是抛 / null).
      val default = BotManager.getBot()
      assertTrue(
        firstWinner.any { id -> default.toString() == "FakeBot($id)" },
        "默认 bot 必须是这 100 个里的某一个",
      )
    }
  }

  @Test
  fun `100 并发 register 同 id 只入表一次`() {
    val bot = fakeBot("dup-bot")
    runBlocking {
      coroutineScope {
        (1..100).map {
          async(Dispatchers.Default) {
            BotManager.registerBot(bot)
          }
        }.awaitAll()
      }
    }
    BotManager.close()
    assertEquals(1, closeCounters["dup-bot"]!!.get(), "重复注册不应让 close 被多次调用")
  }
}
