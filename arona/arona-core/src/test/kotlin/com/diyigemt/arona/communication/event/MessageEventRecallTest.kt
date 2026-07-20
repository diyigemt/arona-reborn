package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.contact.EmptyChannelImpl
import com.diyigemt.arona.communication.contact.EmptyGroupImpl
import com.diyigemt.arona.communication.contact.EmptyGuildChannelMemberImpl
import com.diyigemt.arona.communication.contact.EmptyGuildImpl
import com.diyigemt.arona.communication.contact.EmptyGuildMemberImpl
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.RecallUnsupportedException
import com.diyigemt.arona.communication.message.toMessageChain
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 事件级撤回: 撤回的是收到的 (他人的) 消息, id 取自 message.sourceId.
 */
class MessageEventRecallTest {
  private fun stubBot(isPublic: Boolean = false) =
    StubBot(unitCallOpenapiResult = Result.success(Unit), isPublic = isPublic)

  private fun chain(sourceId: String) = PlainText("blocked").toMessageChain(sourceId, null)

  @Test
  fun `群消息事件撤回用群 id 与来源消息 id`() = runBlocking {
    val bot = stubBot()
    try {
      val sender = EmptyGroupImpl(bot, "g1").members.getOrCreate("member-1")
      val event = TencentGroupMessageEvent(chain("m1"), eventId = "e1", sender = sender)

      val result = event.recall(hideTip = true)

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGroupMessage, call.endpoint)
      assertEquals("g1", call.placeholders["group_openid"])
      assertEquals("m1", call.placeholders["message_id"])
      assertEquals(HttpMethod.Delete, call.request.method)
      // hideTip 传了 true 也不该出现在群端点上.
      assertNull(call.request.url.parameters["hidetip"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `子频道消息事件撤回用 channel id 并带 hidetip`() = runBlocking {
    val bot = stubBot()
    try {
      val channel = EmptyChannelImpl(EmptyGuildImpl(bot, "gu1"), "c1")
      val event = TencentGuildMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = EmptyGuildChannelMemberImpl(channel, "member-1"),
      )

      val result = event.recall(hideTip = true)

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGuildMessage, call.endpoint)
      assertEquals("c1", call.placeholders["channel_id"])
      assertEquals("m1", call.placeholders["message_id"])
      assertEquals("true", call.request.url.parameters["hidetip"])
    } finally {
      bot.close()
    }
  }

  /**
   * 频道消息与频道私信的 subject 都是 Channel, 只有按事件类型分派才能把两者分开.
   * 这条与上一条构成对照: 同为 Channel subject, 端点与占位符必须不同.
   */
  @Test
  fun `频道私信事件撤回走 dms 端点并用 guild id`() = runBlocking {
    val bot = stubBot()
    try {
      val guild = EmptyGuildImpl(bot, "gu1")
      val event = TencentGuildPrivateMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = EmptyGuildMemberImpl(guild, "member-1"),
      )

      val result = event.recall()

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGuildMemberMessage, call.endpoint)
      assertEquals("gu1", call.placeholders["guild_id"])
      assertNull(call.placeholders["channel_id"], "dms 端点不接受 channel_id")
    } finally {
      bot.close()
    }
  }

  /**
   * 单聊与频道私信当前只允许撤回 Bot 自己的消息, 但那是上游权限策略而非本地事实——本次群管理员
   * 撤回能力的放开正说明这类策略会变. 这两条测试钉死"客户端不得自作主张拦截".
   */
  @Test
  fun `单聊事件仍然发出请求交由服务端裁决`() = runBlocking {
    val serverFailure = IllegalStateException("server rejected")
    val bot = StubBot(unitCallOpenapiResult = Result.failure(serverFailure))
    try {
      val event = TencentFriendMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = bot.friends.getOrCreate("u1"),
      )

      val result = event.recall()

      assertEquals(serverFailure, result.exceptionOrNull(), "服务端结论应原样返回")
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteFriendMessage, call.endpoint)
      assertEquals("u1", call.placeholders["openid"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `频道私信事件仍然发出请求交由服务端裁决`() = runBlocking {
    val serverFailure = IllegalStateException("server rejected")
    val bot = StubBot(unitCallOpenapiResult = Result.failure(serverFailure))
    try {
      val event = TencentGuildPrivateMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = EmptyGuildMemberImpl(EmptyGuildImpl(bot, "gu1"), "member-1"),
      )

      val result = event.recall()

      assertEquals(serverFailure, result.exceptionOrNull())
      assertTrue(bot.calls.isNotEmpty(), "不得在本地硬编码 dms 不可撤回")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `公域 Bot 的频道事件在出网前失败`() = runBlocking {
    val bot = stubBot(isPublic = true)
    try {
      val channel = EmptyChannelImpl(EmptyGuildImpl(bot, "gu1"), "c1")
      val event = TencentGuildMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = EmptyGuildChannelMemberImpl(channel, "member-1"),
      )

      val result = event.recall()

      assertTrue(result.isFailure)
      assertIs<RecallUnsupportedException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `公域 Bot 的频道私信事件在出网前失败`() = runBlocking {
    val bot = stubBot(isPublic = true)
    try {
      val event = TencentGuildPrivateMessageEvent(
        chain("m1"),
        eventId = "e1",
        sender = EmptyGuildMemberImpl(EmptyGuildImpl(bot, "gu1"), "member-1"),
      )

      val result = event.recall()

      assertTrue(result.isFailure)
      assertIs<RecallUnsupportedException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `mock 注入消息的空 sourceId 在出网前失败`() = runBlocking {
    val bot = stubBot()
    try {
      val sender = EmptyGroupImpl(bot, "g1").members.getOrCreate("member-1")
      // 与 mockGroupMessage() 同构: PlainText(...).toMessageChain() 的 sourceId 是空串.
      val event = TencentGroupMessageEvent(PlainText("mock").toMessageChain(), eventId = "e1", sender = sender)

      val result = event.recall()

      assertTrue(result.isFailure)
      assertIs<IllegalArgumentException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不得拼出尾部空段的 URL 发出去")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `未知事件子类明确失败而非按 subject 猜端点`() = runBlocking {
    val bot = stubBot()
    try {
      val friend = bot.friends.getOrCreate("u1")
      val event = object : TencentMessageEvent(bot, chain("m1")) {
        override val eventId = "e1"
        override val subject = friend
        override val sender = friend
      }

      val result = event.recall()

      assertTrue(result.isFailure)
      assertIs<RecallUnsupportedException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }
}
