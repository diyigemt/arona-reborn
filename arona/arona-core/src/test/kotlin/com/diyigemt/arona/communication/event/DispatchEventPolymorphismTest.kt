package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
      TencentWebsocketEventType.GROUP_MESSAGE_CREATE,
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

  @Test
  fun `INTERACTION_CREATE 快捷菜单(12)缺 button_id 也能解码 broadcast, eventId 同源 d_id`() {
    // accept() 的 PUT 用成功结果打桩, 贴近真实回执; 也避免将来 accept() 开始传播失败时无关地打破本测试.
    val bot = StubBot(unitCallOpenapiResult = Result.success(Unit))
    try {
      // 关键构造: 信封顶层 id 与 d.id 故意不同; 缺 button_id, 仅给 feature_id —— 复现 type=12 快捷菜单载荷.
      val raw = """
        {
          "id": "envelope-id-XYZ",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "interaction-d-id-123",
            "type": 12,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-9",
            "application_id": "app-1",
            "data": {
              "type": 12,
              "resolved": {
                "feature_id": "feat-42",
                "button_data": "menu-data"
              }
            }
          }
        }
      """.trimIndent()

      val captured = AtomicReference<TencentCallbackButtonEvent?>(null)
      val listener = GlobalEventChannel.subscribeAlways<TencentCallbackButtonEvent> { event ->
        if (event.bot === bot) captured.set(event)
      }
      try {
        runBlocking {
          TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
            TencentDispatchContext(bot),
            TencentWebsocketEventType.INTERACTION_CREATE,
            raw,
          )
          withTimeout(1000L) {
            while (captured.get() == null) delay(10L)
          }
        }

        val event = assertNotNull(captured.get(), "type=12 缺 button_id 也应成功解码并 broadcast")
        assertEquals(TencentWebsocketCallbackButtonType.QuickMenu, event.type, "type=12 应解析为 QuickMenu")
        assertEquals("", event.buttonId, "快捷菜单无 button_id, handler 兜底为空串")
        assertEquals("feat-42", event.featureId, "feature_id 应透传到事件对象")
        assertEquals("menu-data", event.buttonData)
        assertEquals("interaction-d-id-123", event.id, "id 取 d.id")
        // 核心回归 (#1): 被动回复用的 eventId 必须同源于 d.id, 不能再取信封顶层 id.
        assertEquals("interaction-d-id-123", event.eventId, "eventId 应等于 d.id")
        assertNotEquals("envelope-id-XYZ", event.eventId, "eventId 绝不能取信封顶层 id")

        // 分发只广播不回执; 唯一一次 openapi 调用应由 accept() 触发, 且走 PUT /interactions/{interaction_id}.
        runBlocking { event.accept() }
        assertEquals(1, bot.attempts, "分发本身不应发起 openapi 调用, 仅 accept() 触发唯一一次")
        val putCall = bot.calls.single { it.endpoint == TencentEndpoint.Interactions }
        assertEquals("interaction-d-id-123", putCall.placeholders["interaction_id"], "interaction_id 取 d.id")
        assertEquals(HttpMethod.Put, putCall.request.method, "回执必须是 PUT")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE 非按钮类型(13)被显式短路, 不 broadcast 也不回执`() {
    val bot = StubBot()
    try {
      // type=13 消息反馈: button_id 放开可空后仍能解码, 必须由 handler 白名单挡在广播之外,
      // 否则会退化为把 13~20 误当按钮点击(11)处理.
      val raw = """
        {
          "id": "env-2",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-13",
            "type": 13,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-x",
            "data": { "type": 13, "resolved": { "feedback_opt": "LIKE" } }
          }
        }
      """.trimIndent()

      val captured = AtomicReference<TencentCallbackButtonEvent?>(null)
      val listener = GlobalEventChannel.subscribeAlways<TencentCallbackButtonEvent> { event ->
        if (event.bot === bot) captured.set(event)
      }
      try {
        runBlocking {
          TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
            TencentDispatchContext(bot),
            TencentWebsocketEventType.INTERACTION_CREATE,
            raw,
          )
        }
        // broadcast 会在 manager 返回前同步等待所有监听器; type=13 被 handler 短路后根本没有广播, 故可直接断言.
        assertNull(captured.get(), "非 11/12 互动类型不应广播 TencentCallbackButtonEvent")
        assertEquals(0, bot.attempts, "被短路的事件不应触发任何 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }
}
