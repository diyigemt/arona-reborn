package com.diyigemt.arona.communication.command

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.command.CommandSender.Companion.toCommandSender
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.TencentDispatchContext
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.TencentWebsocketDispatchEventManager
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.toMessageChain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

// 锁定 author.username -> TencentGroupMessageEvent.platformUsername -> GroupCommandSender.platformUsername 的透传链路:
// 名字挂在 event/sender 这一逐条消息维度上, 而非缓存的 GroupMember 上, 故同一成员的不同消息可以携带不同的名字.
class GroupSenderPlatformUsernameTest {
  private fun StubBot.groupMember(gid: String, uid: String) =
    groups.getOrCreate(gid).members.getOrCreate(uid)

  private fun groupEvent(bot: StubBot, gid: String, uid: String, username: String?) =
    TencentGroupMessageEvent(
      PlainText("hi").toMessageChain(),
      eventId = "e-$uid",
      sender = bot.groupMember(gid, uid),
      platformUsername = username,
    )

  @Test
  fun `platformUsername 透传到 GroupCommandSender`() {
    val bot = StubBot()
    try {
      val sender = groupEvent(bot, "g1", "u1", "妮露").toCommandSender()
      assertEquals("妮露", sender.platformUsername)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `event 无 platformUsername 时 sender platformUsername 为 null`() {
    val bot = StubBot()
    try {
      val sender = groupEvent(bot, "g1", "u1", null).toCommandSender()
      assertNull(sender.platformUsername)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `同一成员不同消息可携带不同 platformUsername`() {
    val bot = StubBot()
    try {
      // 复用同一 (group, member) id: ContactList 缓存命中, 两次拿到同一 GroupMember 实例;
      // 但名字源于各自的 event, 故两个 sender 读到各自消息的 username, 互不串味.
      val first = groupEvent(bot, "g1", "u1", "旧名").toCommandSender()
      val second = groupEvent(bot, "g1", "u1", "新名").toCommandSender()
      assertSame(first.user, second.user)
      assertEquals("旧名", first.platformUsername)
      assertEquals("新名", second.platformUsername)
    } finally {
      bot.close()
    }
  }

  // ---- handler 端到端: 锁住 "platformUsername = payload.author.username" 这两行 wiring ----

  private fun groupMessageRaw(type: String, username: String?): String {
    val authorUsername = if (username == null) "" else """, "username": "$username""""
    return """
      {
        "id": "evt",
        "op": 0,
        "s": 0,
        "t": "$type",
        "d": {
          "id": "msg-1",
          "author": { "member_openid": "u-1"$authorUsername },
          "content": "/test",
          "timestamp": "0",
          "group_openid": "g-1"
        }
      }
    """.trimIndent()
  }

  private fun dispatchAndCaptureSender(
    type: TencentWebsocketEventType,
    rawJson: String,
  ): GroupCommandSender {
    val bot = StubBot()
    val captured = CompletableDeferred<TencentGroupMessageEvent>()
    val handle = GlobalEventChannel.subscribeAlways<TencentGroupMessageEvent> {
      if (it.bot === bot) captured.complete(it)
    }
    try {
      return runBlocking {
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          type,
          rawJson,
        )
        withTimeout(5000) { captured.await() }.toCommandSender()
      }
    } finally {
      handle.complete()
      bot.close()
    }
  }

  @Test
  fun `GROUP_AT_MESSAGE_CREATE 透传 author username 到 sender`() {
    val sender = dispatchAndCaptureSender(
      TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE,
      groupMessageRaw("GROUP_AT_MESSAGE_CREATE", "妮露"),
    )
    assertEquals("妮露", sender.platformUsername)
  }

  @Test
  fun `GROUP_MESSAGE_CREATE 透传 author username 到 sender`() {
    val sender = dispatchAndCaptureSender(
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
      groupMessageRaw("GROUP_MESSAGE_CREATE", "普拉娜"),
    )
    assertEquals("普拉娜", sender.platformUsername)
  }

  @Test
  fun `旧 payload 缺 author username 时 handler 透传 null`() {
    val sender = dispatchAndCaptureSender(
      TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE,
      groupMessageRaw("GROUP_AT_MESSAGE_CREATE", null),
    )
    assertNull(sender.platformUsername)
  }
}
