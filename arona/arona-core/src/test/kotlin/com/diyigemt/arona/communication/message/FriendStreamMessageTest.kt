package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.command.FriendUserCommandSender
import com.diyigemt.arona.communication.command.sendStreamMessage
import com.diyigemt.arona.communication.contact.FriendUserImpl
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.contact.StubOpenapiCall
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

// 流式消息是文档新接口, wire 形态待 sandbox 实测; 本测试钉住的是本地状态机与路由契约:
// index 仅成功后推进 / 首片无 stream_msg_id 后续必带 / 失败闩锁短路 / 取消闩锁重抛 /
// 被动窗口本地护栏 / streamMessage 自动收尾与 sender 接线.
class FriendStreamMessageTest {

  private fun botWithStreamId(id: String = "stream-1") =
    StubBot(callOpenapiResult = Result.success(TencentStreamMessageResp(id = id)))

  private fun friend(bot: TencentBot, id: String = "friend-1") =
    FriendUserImpl(bot, bot.coroutineContext, id, null)

  private fun session(
    bot: TencentBot,
    sourceMessageId: String? = "source-1",
    eventId: String? = null,
    timeSource: TestTimeSource? = null,
  ) = if (timeSource == null) {
    TencentFriendStreamSession(friend(bot), sourceMessageId, eventId, 1, TencentStreamContentType.TEXT)
  } else {
    TencentFriendStreamSession(friend(bot), sourceMessageId, eventId, 1, TencentStreamContentType.TEXT, timeSource)
  }

  private fun StubOpenapiCall.body() = Json.parseToJsonElement(request.body as String).jsonObject

  @Test
  fun `两片正文加自动终止片的路由与逐片 wire body 正确`() {
    val bot = botWithStreamId()
    try {
      val result = runBlocking {
        friend(bot).streamMessage(
          sourceMessageId = "source-1",
          eventId = "event-1",
          messageSequence = 7,
          contentType = TencentStreamContentType.MARKDOWN,
        ) {
          emit("第一片").getOrThrow()
          emit("第二片").getOrThrow()
        }
      }

      assertTrue(result.isSuccess)
      assertEquals(3, bot.calls.size)
      bot.calls.forEach {
        assertEquals(TencentEndpoint.PostFriendStreamMessage, it.endpoint)
        assertEquals("friend-1", it.placeholders["openid"])
        assertEquals(HttpMethod.Post, it.request.method)
      }

      val first = bot.calls[0].body()
      // 顶层 key 精确集合: 首片不得携带 stream_msg_id, nullable 字段非空时必须在场.
      assertEquals(
        setOf(
          "input_state", "index", "content_type", "content_raw", "msg_seq",
          "msg_id", "event_id", "input_mode", "is_wakeup",
        ),
        first.keys,
      )
      assertEquals(1, first["input_state"]!!.jsonPrimitive.int)
      assertEquals(0, first["index"]!!.jsonPrimitive.int)
      assertEquals("markdown", first["content_type"]!!.jsonPrimitive.content)
      assertEquals("第一片", first["content_raw"]!!.jsonPrimitive.content)
      assertEquals(7, first["msg_seq"]!!.jsonPrimitive.int)
      assertEquals("source-1", first["msg_id"]!!.jsonPrimitive.content)
      assertEquals("event-1", first["event_id"]!!.jsonPrimitive.content)
      assertEquals("append", first["input_mode"]!!.jsonPrimitive.content)
      assertFalse(first["is_wakeup"]!!.jsonPrimitive.boolean)

      // 二片与终止片: 常驻字段每片持续在场, 仅多出 stream_msg_id.
      val followUpKeys = setOf(
        "input_state", "index", "content_type", "content_raw", "msg_seq",
        "msg_id", "event_id", "stream_msg_id", "input_mode", "is_wakeup",
      )
      val second = bot.calls[1].body()
      assertEquals(followUpKeys, second.keys)
      assertEquals(1, second["input_state"]!!.jsonPrimitive.int)
      assertEquals(1, second["index"]!!.jsonPrimitive.int)
      assertEquals("第二片", second["content_raw"]!!.jsonPrimitive.content)
      assertEquals("stream-1", second["stream_msg_id"]!!.jsonPrimitive.content)

      val terminal = bot.calls[2].body()
      assertEquals(followUpKeys, terminal.keys)
      assertEquals(10, terminal["input_state"]!!.jsonPrimitive.int)
      assertEquals(2, terminal["index"]!!.jsonPrimitive.int)
      assertEquals("", terminal["content_raw"]!!.jsonPrimitive.content)
      assertEquals("stream-1", terminal["stream_msg_id"]!!.jsonPrimitive.content)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `NEW 状态可直接 complete 且 null 字段整 key 省略`() {
    val bot = botWithStreamId()
    try {
      val s = session(bot, sourceMessageId = null, eventId = null)
      val result = runBlocking { s.complete("一次完成") }

      assertTrue(result.isSuccess)
      val body = bot.calls.single().body()
      assertEquals(10, body["input_state"]!!.jsonPrimitive.int)
      assertEquals(0, body["index"]!!.jsonPrimitive.int)
      assertEquals("一次完成", body["content_raw"]!!.jsonPrimitive.content)
      // 主动模式 + 首片: 三个 nullable 字段都不该出现, 更不该是显式 null.
      assertFalse("msg_id" in body)
      assertFalse("event_id" in body)
      assertFalse("stream_msg_id" in body)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `complete 后再 emit 或 complete 抛 IllegalStateException`() {
    val bot = botWithStreamId()
    try {
      val s = session(bot)
      runBlocking {
        s.emit("正文").getOrThrow()
        s.complete().getOrThrow()
        assertFailsWith<IllegalStateException> { s.emit("过晚正文") }
        assertFailsWith<IllegalStateException> { s.complete() }
      }
      assertEquals(2, bot.attempts, "COMPLETED 后的调用必须在出网前被拦截")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `任一片失败闩锁会话且后续调用返回同一异常不出网`() {
    val failure = IllegalStateException("server rejected")
    val bot = StubBot(callOpenapiResult = Result.failure(failure))
    try {
      val s = session(bot)
      val first = runBlocking { s.emit("第一片") }
      val second = runBlocking { s.emit("第二片") }
      val terminal = runBlocking { s.complete() }

      assertSame(failure, first.exceptionOrNull())
      assertSame(failure, second.exceptionOrNull())
      assertSame(failure, terminal.exceptionOrNull())
      assertEquals(1, bot.attempts)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `Result_failure 形态的取消还原为抛出并闩锁为投递未知`() {
    // 生产 callOpenapi 用 runCatching 包 HTTP, 协程取消到达会话的真实形态是 Result.failure(CE).
    val cancellation = CancellationException("cancelled inside callOpenapi")
    val bot = StubBot(callOpenapiResult = Result.failure(cancellation))
    try {
      val s = session(bot)
      val thrown = runBlocking {
        assertFailsWith<CancellationException> { s.emit("第一片") }
      }
      assertSame(cancellation, thrown)

      // 闩锁原因不是 CE 本身 (那会把取消伪装成业务失败), 而是"结果未知"的 ISE, cause 挂原始取消.
      val latched = runBlocking { s.emit("第二片") }
      val latchedCause = assertIs<IllegalStateException>(latched.exceptionOrNull())
      assertSame(cancellation, latchedCause.cause)
      assertEquals(1, bot.attempts)
    } finally {
      bot.close()
    }
  }

  private class DirectThrowBot(
    private val delegate: StubBot,
    private val toThrow: Throwable,
  ) : TencentBot by delegate {
    var attempts = 0
      private set

    override suspend fun <T> callOpenapi(
      endpoint: TencentEndpoint,
      decoder: KSerializer<T>,
      urlPlaceHolder: Map<String, String>,
      block: HttpRequestBuilder.() -> Unit,
    ): Result<T> {
      attempts += 1
      throw toThrow
    }
  }

  @Test
  fun `直接抛出形态的取消同样闩锁并原样重抛`() {
    val delegate = StubBot()
    val cancellation = CancellationException("direct cancellation")
    val bot = DirectThrowBot(delegate, cancellation)
    try {
      val s = session(bot)
      val thrown = runBlocking {
        assertFailsWith<CancellationException> { s.emit("第一片") }
      }
      assertSame(cancellation, thrown)

      val latched = runBlocking { s.complete() }
      assertSame(cancellation, assertIs<IllegalStateException>(latched.exceptionOrNull()).cause)
      assertEquals(1, bot.attempts)
    } finally {
      delegate.close()
    }
  }

  @Test
  fun `直接抛出的普通异常同样闩锁且原样记录`() {
    // 生产 callOpenapi 几乎不会直接抛非 CE (runCatching 全包), 但会话不应依赖这一实现细节.
    val delegate = StubBot()
    val boom = IllegalStateException("thrown, not wrapped")
    val bot = DirectThrowBot(delegate, boom)
    try {
      val s = session(bot)
      val first = runBlocking { s.emit("第一片") }
      assertSame(boom, first.exceptionOrNull())

      val second = runBlocking { s.complete() }
      assertSame(boom, second.exceptionOrNull())
      assertEquals(1, bot.attempts)
    } finally {
      delegate.close()
    }
  }

  @Test
  fun `block 吞掉 emit 失败后 streamMessage 仍返回闩锁异常`() {
    // 调用方忽略 emit 的 Result 正常返回时, 自动收尾不得掩盖已闩锁的失败, 也不得再出网补终止片.
    val failure = IllegalStateException("server rejected")
    val bot = StubBot(callOpenapiResult = Result.failure(failure))
    try {
      val result = runBlocking {
        friend(bot).streamMessage(sourceMessageId = "source-1") {
          emit("第一片") // 刻意不 getOrThrow
        }
      }
      assertSame(failure, result.exceptionOrNull())
      assertEquals(1, bot.attempts)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `null 字段省略契约不依赖 Json 配置`() {
    // @EncodeDefault(NEVER) 把省略固化在 DTO 自身: 即便某个 TencentBot 实现的 json 配了
    // encodeDefaults=true, 也不得出现显式 "msg_id":null.
    val aggressiveJson = Json { encodeDefaults = true }
    val encoded = aggressiveJson.encodeToString(
      TencentStreamMessageReq.serializer(),
      TencentStreamMessageReq(
        inputState = 1,
        index = 0,
        contentType = "text",
        contentRaw = "正文",
        messageSequence = 1,
      ),
    )
    val body = Json.parseToJsonElement(encoded).jsonObject
    assertFalse("msg_id" in body)
    assertFalse("event_id" in body)
    assertFalse("stream_msg_id" in body)
    assertEquals("append", body["input_mode"]!!.jsonPrimitive.content)
  }

  @Test
  fun `block 抛取消时原样重抛且不补终止片`() {
    val bot = botWithStreamId()
    val cancellation = CancellationException("caller cancelled")
    try {
      val thrown = runBlocking {
        assertFailsWith<CancellationException> {
          friend(bot).streamMessage(sourceMessageId = "source-1") {
            emit("第一片").getOrThrow()
            throw cancellation
          }
        }
      }
      assertSame(cancellation, thrown)
      assertEquals(1, bot.attempts, "block 取消后不得自动补发终止片")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `block 抛普通异常转为 Result_failure 且不补终止片`() {
    val bot = botWithStreamId()
    val failure = IllegalArgumentException("block failed")
    try {
      val result = runBlocking {
        friend(bot).streamMessage(sourceMessageId = "source-1") { throw failure }
      }
      assertSame(failure, result.exceptionOrNull())
      assertEquals(0, bot.attempts)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `被动模式超过软窗口本地失败而主动模式同时刻不拦`() {
    val passiveBot = botWithStreamId()
    val activeBot = botWithStreamId()
    val timeSource = TestTimeSource()
    try {
      val passive = session(passiveBot, sourceMessageId = "source-1", timeSource = timeSource)
      val active = session(activeBot, sourceMessageId = null, eventId = null, timeSource = timeSource)
      timeSource += 56.minutes

      val passiveResult = runBlocking { passive.emit("过晚") }
      assertTrue(passiveResult.exceptionOrNull()!!.message!!.contains("55-minute"))
      assertEquals(0, passiveBot.attempts, "窗口护栏必须在出网前拦截")
      // 护栏闩锁后同样短路.
      val again = runBlocking { passive.emit("再试") }
      assertSame(passiveResult.exceptionOrNull(), again.exceptionOrNull())

      val activeResult = runBlocking { active.emit("主动模式") }
      assertTrue(activeResult.isSuccess)
      assertEquals(1, activeBot.attempts)
    } finally {
      passiveBot.close()
      activeBot.close()
    }
  }

  @Test
  fun `首个非终止片响应缺 id 时闩锁失败`() {
    val bot = StubBot(callOpenapiResult = Result.success(TencentStreamMessageResp()))
    try {
      val s = session(bot)
      val first = runBlocking { s.emit("第一片") }
      assertTrue(first.exceptionOrNull()!!.message!!.contains("non-blank response id"))
      assertNull(s.streamMessageId)

      val second = runBlocking { s.emit("第二片") }
      assertSame(first.exceptionOrNull(), second.exceptionOrNull())
      assertEquals(1, bot.attempts)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `空白 sourceMessageId 归一为主动模式不编码 msg_id`() {
    val bot = botWithStreamId()
    try {
      // EmptyMessageId="" 这类占位值直接透传给 streamMessage 时不得被当成被动回执.
      val result = runBlocking {
        friend(bot).streamMessage(sourceMessageId = "", eventId = " ") { emit("正文").getOrThrow() }
      }
      assertTrue(result.isSuccess)
      val first = bot.calls[0].body()
      assertFalse("msg_id" in first)
      assertFalse("event_id" in first)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `sendStreamMessage 接线 sourceId eventId 并原子递增 msg_seq`() {
    val bot = botWithStreamId()
    try {
      // toCommandSender() 尚未接线信封 eventId (见 sendStreamMessage KDoc), 此处直接构造 sender
      // 钉住接线契约本身.
      val sender = FriendUserCommandSender(friend(bot, "friend-command"), "source-command", "envelope-event")
      val first = runBlocking { sender.sendStreamMessage { complete("第一条").getOrThrow() } }
      val second = runBlocking { sender.sendStreamMessage { complete("第二条").getOrThrow() } }

      assertTrue(first.isSuccess)
      assertTrue(second.isSuccess)
      assertEquals(2, bot.calls.size)
      assertEquals("friend-command", bot.calls[0].placeholders["openid"])

      val firstBody = bot.calls[0].body()
      val secondBody = bot.calls[1].body()
      assertEquals("source-command", firstBody["msg_id"]!!.jsonPrimitive.content)
      assertEquals("envelope-event", firstBody["event_id"]!!.jsonPrimitive.content)
      assertEquals(1, firstBody["msg_seq"]!!.jsonPrimitive.int)
      assertEquals(2, secondBody["msg_seq"]!!.jsonPrimitive.int)
      assertEquals(3, sender.messageSequence)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `主动语境 sender 的 EmptyMessageId 不进 wire`() {
    val bot = botWithStreamId()
    try {
      val sender = FriendUserCommandSender(friend(bot), "")
      val result = runBlocking { sender.sendStreamMessage { complete("主动").getOrThrow() } }

      assertTrue(result.isSuccess)
      val body = bot.calls.single().body()
      assertFalse("msg_id" in body)
      assertFalse("event_id" in body)
    } finally {
      bot.close()
    }
  }
}
