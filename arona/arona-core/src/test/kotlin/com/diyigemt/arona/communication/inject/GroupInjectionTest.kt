package com.diyigemt.arona.communication.inject

import com.diyigemt.arona.communication.contact.GroupImpl
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.GroupMessagePostSendEvent
import com.diyigemt.arona.communication.event.QuotedMessage
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.message.MessageReceiptImpl
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.toMessageChain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

// 注入窗口的出站契约: capture 零出网、send 去掉伪 msg_id、窗口失效后伪 msg_id 绝不出网.
class GroupInjectionTest {

  private fun stubbedBot() = StubBot(callOpenapiResult = Result.success(MessageReceiptImpl("real-mid", "ts")))

  private fun withBot(block: suspend (StubBot) -> Unit) {
    val bot = stubbedBot()
    try {
      runBlocking { block(bot) }
    } finally {
      bot.close()
    }
  }

  /** 收集发往 [groupId] 的 PostSend, 全局 channel 会收到其他测试的广播, 按群过滤. */
  private fun collectPostSend(groupId: String): Pair<List<GroupMessagePostSendEvent>, () -> Unit> {
    val events = CopyOnWriteArrayList<GroupMessagePostSendEvent>()
    val listener = GlobalEventChannel.subscribeAlways<GroupMessagePostSendEvent> {
      if (it.target.id == groupId) events += it
    }
    return events to { listener.complete() }
  }

  @Test
  fun `capture 窗口内注入回复与无源主动消息都不出网, PostSend 保留注入 id`() = withBot { bot ->
    val group = GroupImpl(bot, bot.coroutineContext, "g-capture")
    val (events, stop) = collectPostSend(group.id)
    try {
      bot.openGroupInjection(group.id, InjectionMode.CAPTURE, 1.minutes).use { window ->
        val reply = group.sendMessage(PlainText("reply").toMessageChain(window.id, null), 1)
        // 插件脱离事件协程另发的消息不带 msg_id, 也必须被截获.
        group.sendMessage(PlainText("side").toMessageChain(), 1)
        val image = group.uploadImage("https://example.invalid/a.png")

        assertEquals(0, bot.attempts, "capture 窗口内不应调用任何 openapi")
        assertTrue(reply!!.id.startsWith(GroupInjections.CAPTURE_RECEIPT_PREFIX))
        assertEquals("https://example.invalid/a.png", image.url)
        assertEquals(listOf(window.id, ""), events.map { it.message.sourceId })
        assertTrue(events.all { it.exception == null })
        assertTrue(reply.recall().exceptionOrNull() is InjectionRejectedException, "capture 回执不能拿去撤回")
      }
    } finally {
      stop()
    }
  }

  @Test
  fun `send 窗口去掉伪 msg_id 后真实发送`() = withBot { bot ->
    val group = GroupImpl(bot, bot.coroutineContext, "g-send")
    val (events, stop) = collectPostSend(group.id)
    try {
      bot.openGroupInjection(group.id, InjectionMode.SEND, 1.minutes).use { window ->
        val receipt = group.sendMessage(PlainText("reply").toMessageChain(window.id, null), 1)

        val body = Json.parseToJsonElement(bot.calls.single().request.body as String).jsonObject
        assertTrue(body["msg_id"]?.jsonPrimitive?.content.isNullOrEmpty(), "伪 msg_id 不能出网: $body")
        assertEquals("real-mid", receipt!!.id)
        assertEquals(window.id, events.single().message.sourceId, "PostSend 仍带注入 id 供收集方关联")
      }
    } finally {
      stop()
    }
  }

  @Test
  fun `窗口关闭或过期后带注入 id 的迟到回复被本地拒绝`() = withBot { bot ->
    val group = GroupImpl(bot, bot.coroutineContext, "g-late")
    val (events, stop) = collectPostSend(group.id)
    try {
      val closed = bot.openGroupInjection(group.id, InjectionMode.SEND, 1.minutes).also { it.close() }
      val expired = bot.openGroupInjection(group.id, InjectionMode.SEND, 1.nanoseconds)
      Thread.sleep(1)

      assertNull(group.sendMessage(PlainText("late").toMessageChain(closed.id, null), 1))
      assertNull(group.sendMessage(PlainText("late").toMessageChain(expired.id, null), 1))
      assertEquals(0, bot.attempts)
      assertTrue(events.all { it.exception is InjectionRejectedException })
      assertEquals(2, events.size)

      // 窗口失效后普通消息恢复真实发送.
      group.sendMessage(PlainText("normal").toMessageChain(), 1)
      assertEquals(1, bot.attempts)
    } finally {
      stop()
    }
  }

  @Test
  fun `capture 窗口关闭后的余波期内迟到的无源消息仍被截获`() = withBot { bot ->
    val group = GroupImpl(bot, bot.coroutineContext, "g-tail")
    val window = bot.openGroupInjection(group.id, InjectionMode.CAPTURE, 1.minutes, captureTail = 1.minutes)
    window.close()
    group.sendMessage(PlainText("late side").toMessageChain(), 1)
    group.sendMessage(PlainText("late reply").toMessageChain(window.id, null), 1)
    group.uploadImage("https://example.invalid/b.png")
    assertEquals(0, bot.attempts, "余波期内不应出网")
    // 余波期内可以开新窗口接管该群.
    bot.openGroupInjection(group.id, InjectionMode.SEND, 1.minutes).close()
    group.sendMessage(PlainText("normal").toMessageChain(), 1)
    assertEquals(1, bot.attempts, "新的 send 窗口关闭后该群恢复真实发送")
  }

  @Test
  fun `注入 id 发往窗口外的目标同样拒绝`() = withBot { bot ->
    val windowGroup = GroupImpl(bot, bot.coroutineContext, "g-a")
    val other = GroupImpl(bot, bot.coroutineContext, "g-b")
    bot.openGroupInjection(windowGroup.id, InjectionMode.SEND, 1.minutes).use { window ->
      assertNull(other.sendMessage(PlainText("x").toMessageChain(window.id, null), 1))
      assertEquals(0, bot.attempts)
    }
  }

  @Test
  fun `同群同时只能有一个活动窗口`() = withBot { bot ->
    val first = bot.openGroupInjection("g-busy", InjectionMode.CAPTURE, 1.minutes)
    assertFailsWith<InjectionRejectedException> { bot.openGroupInjection("g-busy", InjectionMode.SEND, 1.minutes) }
    first.close()
    bot.openGroupInjection("g-busy", InjectionMode.SEND, 1.minutes).close()
  }

  @Test
  fun `分发的事件与真实群消息字段一致且只能分发一次`() = withBot { bot ->
    val received = CompletableDeferred<TencentGroupMessageEvent>()
    val listener = GlobalEventChannel.subscribeAlways<TencentGroupMessageEvent> {
      if (it.group.id == "g-dispatch") received.complete(it)
    }
    try {
      bot.openGroupInjection("g-dispatch", InjectionMode.CAPTURE, 1.minutes).use { window ->
        window.dispatch("m-1", "/帮助", username = "tester", quoted = QuotedMessage("原文", "REFIDX_1"))
        val ev = withTimeout(5_000) { received.await() }

        assertEquals(window.id, ev.message.sourceId)
        assertTrue(ev.isInjected)
        assertTrue(ev.isAtBot)
        assertEquals("m-1", ev.sender.id)
        assertEquals("tester", ev.platformUsername)
        assertEquals(QuotedMessage("原文", "REFIDX_1"), ev.quoted)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+08:00""").matches(ev.timestamp!!), ev.timestamp)
        assertIs<InjectionRejectedException>(runCatching { window.dispatch("m-1", "again") }.exceptionOrNull())
      }
    } finally {
      listener.complete()
    }
  }
}
