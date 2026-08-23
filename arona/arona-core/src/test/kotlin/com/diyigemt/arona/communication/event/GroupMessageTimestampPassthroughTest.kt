package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.toMessageChain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 锁定 payload.timestamp / msg_elements -> TencentGroupMessageEvent.timestamp / quoted 的透传:
// 两个群消息 handler 都要带, mock 路径为 null.
class GroupMessageTimestampPassthroughTest {
  private fun groupMessageRaw(type: String, timestamp: String, msgElements: String? = null) = """
    {
      "id": "evt",
      "op": 0,
      "s": 0,
      "t": "$type",
      "d": {
        "id": "msg-1",
        "author": { "member_openid": "u-1" },
        "content": "hello",
        "timestamp": "$timestamp",
        "group_openid": "g-1"${msgElements?.let { ", \"msg_elements\": $it" } ?: ""}
      }
    }
  """.trimIndent()

  private val quotedElements = """[ { "msg_idx": "REFIDX_ref", "message_type": 103, "content": "在喵～" } ]"""

  private fun dispatchAndCapture(type: TencentWebsocketEventType, rawJson: String): TencentGroupMessageEvent {
    val bot = StubBot()
    val captured = CompletableDeferred<TencentGroupMessageEvent>()
    val handle = GlobalEventChannel.subscribeAlways<TencentGroupMessageEvent> {
      if (it.bot === bot) captured.complete(it)
    }
    try {
      return runBlocking {
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(TencentDispatchContext(bot), type, rawJson)
        withTimeout(5000) { captured.await() }
      }
    } finally {
      handle.complete()
      bot.close()
    }
  }

  @Test
  fun `GROUP_AT_MESSAGE_CREATE 透传 timestamp`() {
    val event = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE,
      groupMessageRaw("GROUP_AT_MESSAGE_CREATE", "2023-07-05T15:06:43+08:00"),
    )
    assertEquals("2023-07-05T15:06:43+08:00", event.timestamp)
  }

  @Test
  fun `GROUP_MESSAGE_CREATE 透传 timestamp`() {
    val event = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw("GROUP_MESSAGE_CREATE", "2023-07-05T15:06:44+08:00"),
    )
    assertEquals("2023-07-05T15:06:44+08:00", event.timestamp)
  }

  @Test
  fun `GROUP_AT_MESSAGE_CREATE 透传 quoted`() {
    val event = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE,
      groupMessageRaw("GROUP_AT_MESSAGE_CREATE", "2023-07-05T15:06:45+08:00", quotedElements),
    )
    assertEquals(QuotedMessage("在喵～", "REFIDX_ref"), event.quoted)
  }

  @Test
  fun `GROUP_MESSAGE_CREATE 透传 quoted`() {
    val event = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw("GROUP_MESSAGE_CREATE", "2023-07-05T15:06:46+08:00", quotedElements),
    )
    assertEquals(QuotedMessage("在喵～", "REFIDX_ref"), event.quoted)
  }

  @Test
  fun `msg_elements 跳过空白元素取第一条有正文的`() {
    val event = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw(
        "GROUP_MESSAGE_CREATE", "2023-07-05T15:06:49+08:00",
        """[ { "msg_idx": "REFIDX_blank", "content": "  " }, { "msg_idx": "REFIDX_ok", "content": " 有效引用 " } ]""",
      ),
    )
    assertEquals(QuotedMessage("有效引用", "REFIDX_ok"), event.quoted)
  }

  @Test
  fun `msg_elements 正文空白或缺失时 quoted 为 null`() {
    val blank = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw("GROUP_MESSAGE_CREATE", "2023-07-05T15:06:47+08:00", """[ { "msg_idx": "REFIDX_x", "content": "  " } ]"""),
    )
    assertNull(blank.quoted)
    val absent = dispatchAndCapture(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw("GROUP_MESSAGE_CREATE", "2023-07-05T15:06:48+08:00"),
    )
    assertNull(absent.quoted)
  }

  @Test
  fun `直接构造的事件 timestamp 默认 null`() {
    val bot = StubBot()
    try {
      val member = bot.groups.getOrCreate("g1").members.getOrCreate("u1")
      val event = TencentGroupMessageEvent(PlainText("hi").toMessageChain(), eventId = "e", sender = member)
      assertNull(event.timestamp)
      assertNull(event.quoted)
    } finally {
      bot.close()
    }
  }
}
