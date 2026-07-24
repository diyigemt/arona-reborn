package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.TencentGuildChannelType
import com.diyigemt.arona.communication.message.MessageReceiptImpl
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentGuildChannelRaw
import com.diyigemt.arona.communication.message.toMessageChain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// call-site 级回归: callMessageOpenApi 是所有 Group/FriendUser 发送的咽喉, 其 setBody 必须走
// encodeTencentMessageForWire. 若未来有人改回 bot.json 的基类泛型编码, wire body 会重新混入
// sealed 判别字段 —— 这里直接从 StubBot 记录的请求体断言, 不给回退留缝.
class SendMessageWireBodyTest {

  private fun stubbedBot() = StubBot(callOpenapiResult = Result.success(MessageReceiptImpl("mid", "ts")))

  @Test
  fun `群消息发送体无判别字段且路由正确`() {
    val bot = stubbedBot()
    try {
      runBlocking {
        val group = GroupImpl(bot, bot.coroutineContext, "g-1")
        group.sendMessage(PlainText("hello").toMessageChain(), 1)

        val call = bot.calls.single()
        assertEquals(TencentEndpoint.PostGroupMessage, call.endpoint)
        assertEquals("g-1", call.placeholders["group_openid"])
        val body = Json.parseToJsonElement(call.request.body as String).jsonObject
        assertFalse("type" in body.keys, "wire body 不应携带 sealed 判别字段: ${body.keys}")
        assertEquals("hello", body["content"]!!.jsonPrimitive.content)
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `子频道消息发送体无判别字段且路由正确`() {
    // callMessageOpenApi 有两个 JSON 编码分支 (Group/FriendUser 与 Channel/GuildMember 的非 multipart),
    // 本用例专门钉第二分支 —— 只测前者时, 第二分支被改回基类泛型编码不会被任何测试发现.
    val bot = stubbedBot()
    try {
      runBlocking {
        val guild = EmptyGuildImpl(bot, "guild-1")
        val channel = ChannelImpl(
          bot,
          guild,
          TencentGuildChannelRaw(
            id = "ch-1",
            guildId = "guild-1",
            name = "test",
            type = TencentGuildChannelType.TEXT,
            position = 0,
            parentId = "",
            ownerId = "",
          ),
        )
        channel.sendMessage(PlainText("gg").toMessageChain(), 1)

        val call = bot.calls.single()
        assertEquals(TencentEndpoint.PostGuildMessage, call.endpoint)
        assertEquals("ch-1", call.placeholders["channel_id"])
        val body = Json.parseToJsonElement(call.request.body as String).jsonObject
        assertFalse("type" in body.keys, "wire body 不应携带 sealed 判别字段: ${body.keys}")
        assertEquals("gg", body["content"]!!.jsonPrimitive.content)
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `单聊消息发送体无判别字段且路由正确`() {
    val bot = stubbedBot()
    try {
      runBlocking {
        val friend = FriendUserImpl(bot, bot.coroutineContext, "u-1", null)
        friend.sendMessage(PlainText("hi").toMessageChain(), 1)

        val call = bot.calls.single()
        assertEquals(TencentEndpoint.PostFriendMessage, call.endpoint)
        assertEquals("u-1", call.placeholders["openid"])
        val body = Json.parseToJsonElement(call.request.body as String).jsonObject
        assertFalse("type" in body.keys, "wire body 不应携带 sealed 判别字段: ${body.keys}")
        assertEquals("hi", body["content"]!!.jsonPrimitive.content)
      }
    } finally {
      bot.close()
    }
  }
}
