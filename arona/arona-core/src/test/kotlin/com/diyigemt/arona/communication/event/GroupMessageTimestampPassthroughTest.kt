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

// 锁定 payload.timestamp -> TencentGroupMessageEvent.timestamp 的透传: 两个群消息 handler 都要带, mock 路径为 null.
class GroupMessageTimestampPassthroughTest {
  private fun groupMessageRaw(type: String, timestamp: String) = """
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
        "group_openid": "g-1"
      }
    }
  """.trimIndent()

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
  fun `直接构造的事件 timestamp 默认 null`() {
    val bot = StubBot()
    try {
      val member = bot.groups.getOrCreate("g1").members.getOrCreate("u1")
      val event = TencentGroupMessageEvent(PlainText("hi").toMessageChain(), eventId = "e", sender = member)
      assertNull(event.timestamp)
    } finally {
      bot.close()
    }
  }
}
