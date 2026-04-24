package com.diyigemt.arona.communication.command

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// 回归保护:
// 旧实现 `var messageSequence: Int = 1` + `.also { messageSequence++ }` 是读改写组合, 并发发送时会 lost update;
// 现在 AbstractCommandSender 里是 AtomicInteger, 接口新增 nextSequence() 做原子 getAndIncrement.
// 下面几条断言任一项失败都指向同一类回归 (lost update / 计数错位).
class CommandSenderSequenceAtomicityTest {

  @BeforeTest
  fun resetSharedSender() {
    // ConsoleCommandSender 是 object, 其他测试也可能访问, 每条用例起始把计数器归位到 1.
    ConsoleCommandSender.messageSequence = 1
  }

  @Test
  fun `nextSequence 顺序调用返回 1 2 3 且 getter 反映下一次将用的值`() {
    assertEquals(1, ConsoleCommandSender.nextSequence())
    assertEquals(2, ConsoleCommandSender.nextSequence())
    assertEquals(3, ConsoleCommandSender.nextSequence())
    assertEquals(4, ConsoleCommandSender.messageSequence, "getter 应反映当前计数器状态 (即下一次 nextSequence 将返回的值)")
  }

  @Test
  fun `messageSequence setter 保留以兼容旧插件 ABI 且会被 nextSequence 读到`() {
    ConsoleCommandSender.messageSequence = 42
    assertEquals(42, ConsoleCommandSender.nextSequence())
    assertEquals(43, ConsoleCommandSender.nextSequence())
  }

  @Test
  fun `并发 500 次 nextSequence 返回的序号集合恰为 1 到 500 无重复无缺失`() {
    runBlocking {
      val results = (1..500).map {
        async(Dispatchers.Default) { ConsoleCommandSender.nextSequence() }
      }.awaitAll()

      val distinct = results.toSortedSet()
      assertEquals(500, distinct.size, "并发 nextSequence 必须不重复, 实际 distinct=${distinct.size}")
      assertEquals(1, distinct.first())
      assertEquals(500, distinct.last())
      assertEquals(501, ConsoleCommandSender.messageSequence, "500 次 getAndIncrement 后计数器应为 501")
    }
  }
}
