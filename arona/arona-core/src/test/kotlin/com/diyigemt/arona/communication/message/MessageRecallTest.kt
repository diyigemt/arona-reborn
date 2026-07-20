package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.contact.EmptyChannelImpl
import com.diyigemt.arona.communication.contact.EmptyGroupImpl
import com.diyigemt.arona.communication.contact.EmptyGuildImpl
import com.diyigemt.arona.communication.contact.EmptyGuildMemberImpl
import com.diyigemt.arona.communication.contact.StubBot
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 撤回路由与发送回执撤回的行为约束.
 */
class MessageRecallTest {
  private fun stubBot(isPublic: Boolean = false) =
    StubBot(unitCallOpenapiResult = Result.success(Unit), isPublic = isPublic)

  private data class RouteCase(
    val destination: RecallDestination,
    val endpoint: TencentEndpoint,
    val routeKey: String,
    val routeValue: String,
    val supportsHideTip: Boolean,
  )

  @Test
  fun `四种场景各自映射到正确的端点与占位符`() = runBlocking {
    val cases = listOf(
      RouteCase(RecallDestination.Friend("u1"), TencentEndpoint.DeleteFriendMessage, "openid", "u1", false),
      RouteCase(RecallDestination.GroupChat("g1"), TencentEndpoint.DeleteGroupMessage, "group_openid", "g1", false),
      RouteCase(RecallDestination.GuildChannel("c1"), TencentEndpoint.DeleteGuildMessage, "channel_id", "c1", true),
      RouteCase(
        RecallDestination.GuildDirect("gu1"),
        TencentEndpoint.DeleteGuildMemberMessage,
        "guild_id",
        "gu1",
        true,
      ),
    )
    cases.forEach { case ->
      val bot = stubBot()
      try {
        val result = bot.recallMessage(case.destination, messageId = "m1", hideTip = true)

        assertTrue(result.isSuccess, "${case.destination} 应成功")
        val call = bot.calls.single()
        assertEquals(case.endpoint, call.endpoint)
        // 断言整个 key 集合而非单个 key: 多带/漏带占位符同样是路由缺陷.
        assertEquals(setOf(case.routeKey, "message_id"), call.placeholders.keys, "${case.destination} 占位符集合")
        assertEquals(case.routeValue, call.placeholders[case.routeKey])
        assertEquals("m1", call.placeholders["message_id"])
        assertEquals(HttpMethod.Delete, call.request.method)
        if (case.supportsHideTip) {
          assertEquals("true", call.request.url.parameters["hidetip"], "${case.destination} 应带 hidetip")
        } else {
          assertNull(call.request.url.parameters["hidetip"], "${case.destination} 不该带 hidetip")
        }
      } finally {
        bot.close()
      }
    }
  }

  @Test
  fun `hideTip 为 false 时频道端点仍显式带上参数`() = runBlocking {
    val bot = stubBot()
    try {
      val result = bot.recallMessage(RecallDestination.GuildChannel("c1"), "m1", hideTip = false)

      assertTrue(result.isSuccess)
      assertEquals("false", bot.calls.single().request.url.parameters["hidetip"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `空 messageId 在出网前失败`() = runBlocking {
    val bot = stubBot()
    try {
      val result = bot.recallMessage(RecallDestination.GroupChat("g1"), "  ", hideTip = false)

      assertTrue(result.isFailure)
      assertIs<IllegalArgumentException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "空 id 不该发起任何请求")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `公域 Bot 调用私域专属端点在出网前失败`() = runBlocking {
    val bot = stubBot(isPublic = true)
    try {
      val result = bot.recallMessage(RecallDestination.GuildChannel("c1"), "m1", hideTip = false)

      assertTrue(result.isFailure)
      assertIs<RecallUnsupportedException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `频道私信回执用 guild id 而非成员 id 或私信 channel id`() = runBlocking {
    val bot = stubBot()
    try {
      val guild = EmptyGuildImpl(bot, "gu1")
      val member = EmptyGuildMemberImpl(guild, "member-1")
      val receipt = MessageReceipt(MessageReceiptImpl(id = "m1"), member)

      val result = receipt.recall(hideTip = true)

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGuildMemberMessage, call.endpoint)
      assertEquals("gu1", call.placeholders["guild_id"])
      // 历史缺陷是传 `channel_id to 成员 id`, 这里正面钉死两个错值都不得出现.
      assertTrue("member-1" !in call.placeholders.values, "不得把成员 id 当作路由参数")
      assertNull(call.placeholders["channel_id"], "dms 端点不接受 channel_id")
      assertEquals("true", call.request.url.parameters["hidetip"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `子频道回执用 channel id`() = runBlocking {
    val bot = stubBot()
    try {
      val channel = EmptyChannelImpl(EmptyGuildImpl(bot, "gu1"), "c1")
      val receipt = MessageReceipt(MessageReceiptImpl(id = "m1"), channel)

      val result = receipt.recall()

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGuildMessage, call.endpoint)
      assertEquals("c1", call.placeholders["channel_id"])
      assertNull(call.placeholders["guild_id"], "channels 端点不接受 guild_id")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `群回执用 group openid`() = runBlocking {
    val bot = stubBot()
    try {
      val receipt = MessageReceipt(MessageReceiptImpl(id = "m1"), EmptyGroupImpl(bot, "g1"))

      val result = receipt.recall()

      assertTrue(result.isSuccess)
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.DeleteGroupMessage, call.endpoint)
      assertEquals("g1", call.placeholders["group_openid"])
      assertNull(call.request.url.parameters["hidetip"], "群端点不该带 hidetip")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `无法映射的回执目标明确失败而非静默 no-op`() = runBlocking {
    val bot = stubBot()
    try {
      // TencentBot 自身是 Contact 但不是任何消息的接收方, 旧实现会走空 when 静默返回.
      val receipt = MessageReceipt(MessageReceiptImpl(id = "m1"), bot)

      val result = receipt.recall()

      assertTrue(result.isFailure)
      assertIs<RecallUnsupportedException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `空回执 id 在出网前失败`() = runBlocking {
    val bot = stubBot()
    try {
      val receipt = MessageReceipt(MessageReceiptImpl(id = ""), EmptyGroupImpl(bot, "g1"))

      val result = receipt.recall()

      assertTrue(result.isFailure)
      assertIs<IllegalArgumentException>(result.exceptionOrNull())
      assertEquals(0, bot.attempts, "不该进入 openapi")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `服务端失败原样透传给调用方`() = runBlocking {
    val serverFailure = IllegalStateException("server said no")
    val bot = StubBot(unitCallOpenapiResult = Result.failure(serverFailure))
    try {
      val receipt = MessageReceipt(MessageReceiptImpl(id = "m1"), EmptyGroupImpl(bot, "g1"))

      val result = receipt.recall()

      assertTrue(result.isFailure)
      assertEquals(serverFailure, result.exceptionOrNull(), "不得把服务端异常改写成本地异常")
    } finally {
      bot.close()
    }
  }
}
