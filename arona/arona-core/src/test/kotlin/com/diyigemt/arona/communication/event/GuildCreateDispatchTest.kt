package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.message.TencentWebsocketPayload0
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

// 回归保护: 死枚举 A("GUILD_CREATE") 曾让 fromValue("GUILD_CREATE") 解析失配, 事件在 dispatch
// 层被静默丢弃. 本测试必须走 fromValue 的字符串路径 (而不是直接传枚举常量), 才能覆盖
// "wire 字符串 -> 枚举 -> handler" 整条链 —— 这正是当年 bug 的端到端形态.
class GuildCreateDispatchTest {

  @Test
  fun `GUILD_CREATE 经字符串解析路径分发后广播 TencentGuildAddEvent 且 guild 入缓存`() {
    val bot = StubBot()
    val received = mutableListOf<TencentGuildAddEvent>()
    // 按 bot 过滤, 防并行测试经 GlobalEventChannel 串扰.
    val listener = GlobalEventChannel.subscribeAlways<TencentGuildAddEvent> { if (it.bot === bot) received.add(it) }
    try {
      runBlocking {
        val raw = """
          {
            "id": "event-guild-create",
            "op": 0,
            "s": 0,
            "t": "GUILD_CREATE",
            "d": {
              "id": "guild-1",
              "name": "n",
              "icon": "",
              "owner_id": "owner",
              "owner": false,
              "member_count": 0,
              "max_members": 0,
              "description": "",
              "joined_at": "0",
              "op_user_id": "inviter"
            }
          }
        """.trimIndent()
        // 与生产 dispatchWebhookEvent 同构: 从信封的 "t" 字段解出事件类型, 而不是手写枚举常量,
        // 让 "信封 JSON -> 枚举 -> handler" 整条链都被覆盖.
        val resolved = bot.json.decodeFromString(TencentWebsocketPayload0.serializer(), raw).type
        assertSame(TencentWebsocketEventType.GUILD_CREATE, resolved)
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          resolved,
          raw,
        )

        assertEquals(1, received.size, "GUILD_CREATE 必须广播恰好一次 TencentGuildAddEvent")
        assertEquals("inviter", received.single().user.id)
        assertNotNull(bot.guilds["guild-1"], "GUILD_CREATE 处理后 guild 应进入缓存")
      }
    } finally {
      listener.complete()
      bot.close()
    }
  }
}
