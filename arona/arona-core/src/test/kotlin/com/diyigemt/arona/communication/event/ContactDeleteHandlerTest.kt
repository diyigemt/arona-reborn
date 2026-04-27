package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 回归保护:
// - 旧实现在 GROUP_DEL_ROBOT / FRIEND_DEL 路径上调 getOrCreate(...) 而非 remove(...), 实质把删除事件
//   退化成"反向写缓存". 下面的用例确认事件处理后 cache 里被删除的实体真的不在.
// - GUILD_DELETE 在旧实现里完全 noop (枚举有定义, dispatch registry 没登记); 新增 handler 必须保证
//   guild 被 remove + 其 SupervisorJob 被 cancel, 不留 Job 子树.
class ContactDeleteHandlerTest {

  @Test
  fun `GROUP_DEL_ROBOT 处理后 bot groups 缓存被清空`() {
    val bot = StubBot()
    try {
      runBlocking {
        // 先模拟有缓存
        bot.groups.getOrCreate("g-1")
        assertNotNull(bot.groups["g-1"], "前置: 缓存里要有 g-1")

        val raw = """
          {
            "id": "event-1",
            "op": 0,
            "s": 0,
            "t": "GROUP_DEL_ROBOT",
            "d": {
              "group_openid": "g-1",
              "op_member_openid": "u-1",
              "timestamp": 0
            }
          }
        """.trimIndent()
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.GROUP_DEL_ROBOT,
          raw,
        )

        assertNull(bot.groups["g-1"], "GROUP_DEL_ROBOT 处理后 cache 里不应再有 g-1")
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `FRIEND_DEL 处理后 bot friends 缓存被清空`() {
    val bot = StubBot()
    try {
      runBlocking {
        bot.friends.getOrCreate("u-2")
        assertNotNull(bot.friends["u-2"])

        val raw = """
          {
            "id": "event-2",
            "op": 0,
            "s": 0,
            "t": "FRIEND_DEL",
            "d": {
              "openid": "u-2",
              "timestamp": "0"
            }
          }
        """.trimIndent()
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.FRIEND_DEL,
          raw,
        )

        assertNull(bot.friends["u-2"], "FRIEND_DEL 处理后 cache 里不应再有 u-2")
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `GUILD_DELETE 处理后 bot guilds 缓存被清空, 原 guild 的 SupervisorJob 被 cancel`() {
    val bot = StubBot()
    try {
      runBlocking {
        val before = bot.guilds.getOrCreate("guild-3")
        // 旧 guild 走 owns scope, 它必须有自己的 Job.
        val ownsJob = assertNotNull(before.coroutineContext[Job])
        assertTrue(before.ownsCoroutineScope, "顶层 guild 应是 owns 模式")

        val raw = """
          {
            "id": "event-3",
            "op": 0,
            "s": 0,
            "t": "GUILD_DELETE",
            "d": {
              "id": "guild-3",
              "name": "n",
              "icon": "",
              "owner_id": "owner",
              "owner": false,
              "member_count": 0,
              "max_members": 0,
              "description": "",
              "joined_at": "0",
              "op_user_id": "kicker"
            }
          }
        """.trimIndent()
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.GUILD_DELETE,
          raw,
        )

        assertNull(bot.guilds["guild-3"], "GUILD_DELETE 处理后 cache 里不应再有 guild-3")
        assertTrue(ownsJob.isCancelled, "被 remove 的 owns-scope guild 必须被 cancel")
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `GROUP_DEL_ROBOT 在 listener 抛异常路径上仍会清缓存 (try-finally 兜底)`() {
    val bot = StubBot()
    val crashingListener = GlobalEventChannel.subscribeAlways<TencentGroupDeleteEvent> { _ ->
      throw IllegalStateException("listener crash")
    }
    try {
      runBlocking {
        bot.groups.getOrCreate("g-finally")
        val raw = """
          {
            "id": "event-finally",
            "op": 0,
            "s": 0,
            "t": "GROUP_DEL_ROBOT",
            "d": {
              "group_openid": "g-finally",
              "op_member_openid": "u-x",
              "timestamp": 0
            }
          }
        """.trimIndent()
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.GROUP_DEL_ROBOT,
          raw,
        )

        assertNull(bot.groups["g-finally"], "listener 抛异常不应阻止 finally remove")
        // listener 抛出的 IllegalStateException 不应让父 bot scope 被取消
        assertFalse(bot.coroutineContext[Job]?.isCancelled == true)
      }
    } finally {
      crashingListener.complete()
      bot.close()
    }
  }
}
