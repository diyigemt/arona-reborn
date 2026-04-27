package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// 回归保护:
// - 旧实现 TencentWebsocketDispatchEventManager 依赖 `handler::class.declaredFunctions.firstOrNull()?.callSuspend(...)`
//   反射把事件从 source 分发到具体 handler. 任何参数错配 / handler 签名漂移都被反射吞掉, 没有编译期保护.
// - 新实现是多态 handler.handle(ctx, payload, eventId) 普通方法调用. 下面的用例通过真实 webhook payload
//   串走 manager.handleTencentDispatchEvent, 把"decode → 调 handler → broadcast"端到端锁住.
class DispatchEventPolymorphismTest {

  @Test
  fun `C2C 消息载荷经 manager 分发后 broadcast TencentFriendMessageEvent`() {
    val bot = StubBot()
    try {
      val raw = """
        {
          "id": "event-id-1",
          "op": 0,
          "s": 0,
          "t": "C2C_MESSAGE_CREATE",
          "d": {
            "id": "msg-id-1",
            "author": {"id": "u-1", "user_openid": "u-1"},
            "content": "hello",
            "timestamp": "0"
          }
        }
      """.trimIndent()

      val captured = AtomicReference<TencentFriendMessageEvent?>(null)
      val listener = GlobalEventChannel.subscribeAlways<TencentFriendMessageEvent> { event ->
        if (event.sender.bot === bot) {
          captured.set(event)
        }
      }
      try {
        runBlocking {
          TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
            TencentDispatchContext(bot),
            TencentWebsocketEventType.C2C_MESSAGE_CREATE,
            raw,
          )
          // listener 走 launch, 等它回写 captured.
          withTimeout(1000L) {
            while (captured.get() == null) {
              kotlinx.coroutines.delay(10L)
            }
          }
        }

        val event = assertNotNull(captured.get())
        assertEquals("u-1", event.sender.id, "friend handler 应把 payload.author.id 作为 sender id")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `未知 event type 分发时 noop 不抛异常`() {
    val bot = StubBot()
    try {
      runBlocking {
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.NULL,
          "{}",
        )
      }
      // 走到这里就说明未注册的 event 没炸.
    } finally {
      bot.close()
    }
  }

  @Test
  fun `decode 失败时 manager 记录日志但不向上抛异常`() {
    val bot = StubBot()
    try {
      runBlocking {
        // payload 结构显然不符合 TencentFriendMessageRaw, manager 的 runCatching onFailure 仅 log.
        TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
          TencentDispatchContext(bot),
          TencentWebsocketEventType.C2C_MESSAGE_CREATE,
          """{"d": {"not a valid payload": 1}}""",
        )
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `dispatch registry 必须严格覆盖预期 event type 集合`() {
    // 显式清单 vs 反射扫描的 trade-off: 新增 handler 忘记登记会静默退化成 noop. 这条用例强制"加 handler
    // 必须同步更新期望集", 把漂移从运行时隐 bug 变成编译/测试期显式失败.
    val expected = setOf(
      TencentWebsocketEventType.MESSAGE_CREATE,
      TencentWebsocketEventType.AT_MESSAGE_CREATE,
      TencentWebsocketEventType.DIRECT_MESSAGE_CREATE,
      TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE,
      TencentWebsocketEventType.C2C_MESSAGE_CREATE,
      TencentWebsocketEventType.GUILD_CREATE,
      TencentWebsocketEventType.GUILD_DELETE,
      TencentWebsocketEventType.GROUP_ADD_ROBOT,
      TencentWebsocketEventType.FRIEND_ADD,
      TencentWebsocketEventType.GROUP_DEL_ROBOT,
      TencentWebsocketEventType.FRIEND_DEL,
      TencentWebsocketEventType.INTERACTION_CREATE,
    )
    assertEquals(expected, TencentWebsocketDispatchEventManager.registeredEventTypes())
  }
}
