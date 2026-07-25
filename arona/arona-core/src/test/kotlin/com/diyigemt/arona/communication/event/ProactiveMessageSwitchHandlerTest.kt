package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 回归保护: GROUP/C2C_MSG_REJECT/RECEIVE 四个开关事件曾"有枚举无 handler", 在 dispatch 层被静默
// 丢弃. 本测试从原始 webhook 信封走 handleTencentDispatchEvent 全链, 钉住广播事件的字段映射.
// broadcast 在 manager 返回前同步等待全部 listener (先例见 GuildCreateDispatchTest), 无需轮询.
class ProactiveMessageSwitchHandlerTest {

  private fun dispatchAndAssert(
    type: TencentWebsocketEventType,
    envelopeId: String,
    data: String,
    assertEvent: (TencentProactiveMessageSwitchEvent) -> Unit,
  ) {
    val bot = StubBot()
    val captured = AtomicReference<TencentProactiveMessageSwitchEvent?>(null)
    // 按 bot 过滤, 防并行测试经 GlobalEventChannel 串扰.
    val listener = GlobalEventChannel.subscribeAlways<TencentProactiveMessageSwitchEvent> {
      if (it.bot === bot) captured.set(it)
    }
    try {
      runBlocking {
        val raw = """
          {
            "id": "$envelopeId",
            "op": 0,
            "s": 0,
            "t": "${type.type}",
            "d": $data
          }
        """.trimIndent()
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(TencentDispatchContext(bot), type, raw)
      }
      assertEvent(assertNotNull(captured.get(), "$type 必须广播主动消息开关事件"))
      assertEquals(0, bot.attempts, "开关事件 handler 不应发起任何 openapi 调用")
    } finally {
      listener.complete()
      bot.close()
    }
  }

  @Test
  fun `GROUP_MSG_REJECT 广播群拒绝事件且字段映射正确`() {
    dispatchAndAssert(
      TencentWebsocketEventType.GROUP_MSG_REJECT,
      envelopeId = "env-group-reject",
      data = """{"group_openid": "g-1", "op_member_openid": "op-1", "timestamp": 101}""",
    ) {
      val event = assertIs<TencentGroupMsgRejectEvent>(it)
      assertEquals("g-1", event.group.id)
      assertSame(event.group, event.subject, "subject 应即群本身")
      assertEquals("op-1", event.operator.id)
      assertFalse(event.accept)
      assertEquals(101L, event.timestamp)
      assertEquals("env-group-reject", event.eventId)
    }
  }

  @Test
  fun `GROUP_MSG_RECEIVE 广播群允许事件`() {
    dispatchAndAssert(
      TencentWebsocketEventType.GROUP_MSG_RECEIVE,
      envelopeId = "env-group-receive",
      data = """{"group_openid": "g-2", "op_member_openid": "op-2", "timestamp": 202}""",
    ) {
      val event = assertIs<TencentGroupMsgReceiveEvent>(it)
      assertEquals("g-2", event.group.id)
      assertEquals("op-2", event.operator.id)
      assertTrue(event.accept)
      assertEquals(202L, event.timestamp)
      assertEquals("env-group-receive", event.eventId)
    }
  }

  @Test
  fun `C2C_MSG_REJECT 广播好友拒绝事件且字符串 timestamp 归一化为 Long`() {
    dispatchAndAssert(
      TencentWebsocketEventType.C2C_MSG_REJECT,
      envelopeId = "env-friend-reject",
      data = """{"openid": "f-1", "timestamp": "303"}""",
    ) {
      val event = assertIs<TencentFriendMsgRejectEvent>(it)
      assertEquals("f-1", event.friend.id)
      assertSame(event.friend, event.subject, "subject 应即好友本身")
      assertFalse(event.accept)
      assertEquals(303L, event.timestamp)
      assertEquals("env-friend-reject", event.eventId)
    }
  }

  @Test
  fun `C2C_MSG_RECEIVE 广播好友允许事件`() {
    dispatchAndAssert(
      TencentWebsocketEventType.C2C_MSG_RECEIVE,
      envelopeId = "env-friend-receive",
      data = """{"openid": "f-2", "timestamp": "404"}""",
    ) {
      val event = assertIs<TencentFriendMsgReceiveEvent>(it)
      assertEquals("f-2", event.friend.id)
      assertTrue(event.accept)
      assertEquals(404L, event.timestamp)
      assertEquals("env-friend-receive", event.eventId)
    }
  }

  @Test
  fun `好友侧非法 timestamp 归一化为 0 而非丢事件`() {
    // 0 在持久化的乱序防回写里是"最旧": 不会覆盖 timestamp > 0 的已有状态 (同为 0 时仍
    // arrival-wins), 因此畸形 timestamp 不值得丢弃整个开关信号.
    dispatchAndAssert(
      TencentWebsocketEventType.C2C_MSG_RECEIVE,
      envelopeId = "env-bad-ts",
      data = """{"openid": "f-bad-ts", "timestamp": "abc"}""",
    ) {
      val event = assertIs<TencentFriendMsgReceiveEvent>(it)
      assertEquals("f-bad-ts", event.friend.id)
      assertEquals(0L, event.timestamp)
      assertEquals("env-bad-ts", event.eventId)
    }
  }
}
