package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.message.TencentWebsocketCallbackButtonResp
import com.diyigemt.arona.communication.message.TencentWebsocketPayload
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 回归保护:
// - 旧实现 TencentWebsocketDispatchEventManager 依赖 `handler::class.declaredFunctions.firstOrNull()?.callSuspend(...)`
//   反射把事件从 source 分发到具体 handler. 任何参数错配 / handler 签名漂移都被反射吞掉, 没有编译期保护.
// - 新实现是多态 handler.handle(ctx, payload, eventId) 普通方法调用. 下面的用例通过真实 webhook payload
//   串走 manager.handleTencentDispatchEvent, 把"decode → 调 handler → broadcast"端到端锁住.
class DispatchEventPolymorphismTest {

  /**
   * 单独解出 webhook 信封里的 `d`(与 manager dispatch 用同一套 DTO / Json 语义). 负向用例借它先断言
   * "raw 确实能解码且带有触发目标 guard 的属性", 排除"因 decode 失败而恰好不广播"的假阳性.
   */
  private fun decodeInteractionData(bot: StubBot, raw: String): TencentWebsocketCallbackButtonResp =
    bot.json.decodeFromString(
      TencentWebsocketPayload.serializer(TencentWebsocketCallbackButtonResp.serializer()),
      raw,
    ).data

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

  @Test
  fun `INTERACTION_CREATE 未知 chat_type(99) fail-closed, 不 broadcast 也不误路由`() {
    val bot = StubBot()
    try {
      // 关键: chat_type=99 在旧实现里 fromValue 兜底成 Group, 且这里故意补齐群路由字段, 让旧实现真的会 broadcast——
      // 否则旧实现也会因缺群字段而短路, 测试在修复前即通过, 保护不住本次 fail-closed 收敛.
      val raw = """
        {
          "id": "env-unknown-chat",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-unknown-chat",
            "type": 11,
            "chat_type": 99,
            "timestamp": "0",
            "group_openid": "group-old-fallback",
            "group_member_openid": "member-old-fallback",
            "data": { "type": 11, "resolved": { "button_id": "b-1", "button_data": "data-1" } }
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
        assertEquals(
          TencentWebsocketCallbackButtonChatType.Unknown,
          decodeInteractionData(bot, raw).chatType,
          "前置: raw 应解码成功且 chat_type=99 落到 Unknown, 否则本用例保护的不是 chat guard",
        )
        assertNull(captured.get(), "未知 chat_type 不应被误当 Group 广播")
        assertEquals(0, bot.attempts, "fail-closed 的事件不应触发任何 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE 外层内层 type 不一致 fail-closed, 不 broadcast`() {
    val bot = StubBot()
    try {
      // 外层 d.type=11 能过按钮白名单, 但内层 d.data.type=13; 路由字段齐全, 仅靠 type 不一致触发短路——
      // 防止"外层伪装成按钮点击、内层实为无需 PUT 的类型"的畸形载荷被误广播 / 误回执.
      val raw = """
        {
          "id": "env-type-mismatch",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-type-mismatch",
            "type": 11,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-mm",
            "data": { "type": 13, "resolved": { "button_id": "b-1", "button_data": "data-1" } }
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
        val d = decodeInteractionData(bot, raw)
        assertEquals(TencentWebsocketCallbackButtonType.MessageButton, d.type, "前置: 外层 type=11")
        assertEquals(TencentWebsocketCallbackButtonType.Unknown, d.data.type, "前置: 内层 type=13→Unknown, 与外层不一致")
        assertNull(captured.get(), "外层/内层 type 不一致的畸形载荷不应广播")
        assertEquals(0, bot.attempts, "fail-closed 的事件不应触发任何 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE 按钮点击(11)缺 button_id fail-closed, 不 broadcast`() {
    val bot = StubBot()
    try {
      // type=11 必带 button_id(事件对象 KDoc 约束). 仅 resolved.button_id 缺失, 其余字段齐全,
      // 触发按类型字段校验短路——避免下游拿到空 buttonId 误匹配.
      val raw = """
        {
          "id": "env-no-btn",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-no-btn",
            "type": 11,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-nb",
            "data": { "type": 11, "resolved": { "button_data": "data-1" } }
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
        val d = decodeInteractionData(bot, raw)
        assertEquals(TencentWebsocketCallbackButtonType.MessageButton, d.type, "前置: 内外层 type 均为 11")
        assertNull(d.data.resolved.buttonId, "前置: resolved.button_id 确实缺失")
        assertNull(captured.get(), "type=11 缺 button_id 的畸形载荷不应广播")
        assertEquals(0, bot.attempts, "fail-closed 的事件不应触发任何 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE 按钮点击(11)群路由正常广播, accept 回执失败以 Result_failure 返回不抛`() {
    // 一并覆盖: type=11 正向路径 + Group 路由 + 按类型字段校验放行 + accept() 回执失败可观测.
    // callOpenapi(Unit) 打桩为 failure, 断言 accept() 不抛且如实返回 Result.failure.
    val bot = StubBot(unitCallOpenapiResult = Result.failure(IllegalStateException("ack boom")))
    try {
      val raw = """
        {
          "id": "env-btn-group",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-btn-group",
            "type": 11,
            "chat_type": 1,
            "timestamp": "0",
            "group_openid": "group-1",
            "group_member_openid": "member-1",
            "data": { "type": 11, "resolved": { "button_id": "confirm", "button_data": "confirm-data" } }
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

        val event = assertNotNull(captured.get(), "合法 type=11 群按钮点击应成功广播")
        assertEquals(TencentWebsocketCallbackButtonType.MessageButton, event.type)
        assertEquals("confirm", event.buttonId, "type=11 的 button_id 应透传")
        assertEquals("d-btn-group", event.id)
        assertEquals("d-btn-group", event.eventId, "eventId 同源 d.id")

        // 回执 PUT 打桩失败: accept() 必须以 Result.failure 返回而非抛出, 且实际发起了一次 PUT.
        val result = runBlocking { event.accept() }
        assertTrue(result.isFailure, "回执 PUT 失败应以 Result.failure 返回而非抛出")
        assertEquals(
          "ack boom", result.exceptionOrNull()?.message,
          "返回的应是 Stub 注入的回执失败, 而非请求构造阶段的其他 failure",
        )
        assertEquals(1, bot.attempts, "accept() 应发起唯一一次 openapi 调用")
        val putCall = bot.calls.single { it.endpoint == TencentEndpoint.Interactions }
        assertEquals("d-btn-group", putCall.placeholders["interaction_id"], "interaction_id 取 d.id")
        assertEquals(HttpMethod.Put, putCall.request.method, "回执必须是 PUT")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE 快捷菜单(12)缺 feature_id fail-closed, 不 broadcast`() {
    val bot = StubBot()
    try {
      // type=12 必带 feature_id(事件对象 KDoc 约束); 缺 button_id 是合法的. 仅 feature_id 缺失,
      // 触发按类型字段校验的 QuickMenu 分支短路——与缺 button_id 用例对称, 补齐 E 的第二条分支回归.
      val raw = """
        {
          "id": "env-no-feat",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-no-feat",
            "type": 12,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-nf",
            "data": { "type": 12, "resolved": { "button_data": "menu-data" } }
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
        val d = decodeInteractionData(bot, raw)
        assertEquals(TencentWebsocketCallbackButtonType.QuickMenu, d.type, "前置: type=12")
        assertNull(d.data.resolved.featureId, "前置: resolved.feature_id 确实缺失")
        assertNull(captured.get(), "type=12 缺 feature_id 的畸形载荷不应广播")
        assertEquals(0, bot.attempts, "fail-closed 的事件不应触发任何 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `INTERACTION_CREATE accept 遇协程取消时向上抛 CancellationException 而非吞成 Result_failure`() {
    // callOpenapi 底层 runCatching 会把 CancellationException 也收进 Result.failure; reject() 必须显式重抛,
    // 否则取消无法传播、破坏结构化并发. 这里用 Stub 注入取消, 断言 accept() 抛出而非静默返回 failure.
    val bot = StubBot(unitCallOpenapiResult = Result.failure(CancellationException("cancel boom")))
    try {
      val raw = """
        {
          "id": "env-cancel",
          "op": 0,
          "t": "INTERACTION_CREATE",
          "d": {
            "id": "d-cancel",
            "type": 12,
            "chat_type": 2,
            "timestamp": "0",
            "user_openid": "user-c",
            "data": { "type": 12, "resolved": { "feature_id": "feat-c", "button_data": "menu-data" } }
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
        val event = assertNotNull(captured.get())
        runBlocking {
          // assertFailsWith 是 inline, 其 block 在此 runBlocking 的 suspend 上下文内调用 accept() 合法;
          // 取消异常被它就地捕获, 不会逃逸去取消 runBlocking 本身.
          assertFailsWith<CancellationException>("回执遇取消必须向上抛出, 不能吞成 Result.failure") {
            event.accept()
          }
        }
        // accept() 确实进入了 openapi(拿到注入的取消), 只是以异常形式向上抛.
        assertEquals(1, bot.attempts, "accept() 应发起唯一一次 openapi 调用")
      } finally {
        listener.complete()
      }
    } finally {
      bot.close()
    }
  }
}
