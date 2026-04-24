package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.event.EventChannel
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.GroupMessagePostSendEvent
import com.diyigemt.arona.communication.event.GroupMessagePreSendEvent
import com.diyigemt.arona.communication.event.TencentBotEvent
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageReceipt
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.toMessageChain
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

// 回归保护:
// 旧实现 pre-send broadcast 抛异常或构造函数抛异常时直接 `return null`, 完全不发 post-send,
// 破坏了 pre/post 成对契约 (审计/限流/回执类 listener 看不到这次尝试). 本文件锁住新契约.
class CallMessageOpenApiEventSymmetryTest {

  @Test
  fun `pre-send 构造异常时仍触发 post-send 并回传真实异常与原始 body`() {
    val bot = StubBot()
    try {
      runBlocking {
        val group = GroupImpl(bot, bot.coroutineContext, "test-group")

        val postSendCalls = AtomicInteger(0)
        val captured = AtomicReference<GroupMessagePostSendEvent?>(null)

        val listener = GlobalEventChannel.subscribeAlways<GroupMessagePostSendEvent> { event ->
          // 全局 channel 会收其他测试留下的广播, 按 target 实例过滤只认本次.
          if (event.target === group) {
            postSendCalls.incrementAndGet()
            captured.set(event)
          }
        }

        try {
          val body = PlainText("hello").toMessageChain()
          val boom = IllegalStateException("boom from pre-send constructor")

          val result = group.callMessageOpenApi<Group>(
            endpoint = TencentEndpoint.PostGroupMessage,
            urlPlaceHolder = mapOf("group_openid" to group.id),
            body = body,
            messageSequence = 1,
            preSendEventConstructor = { _, _ -> throw boom },
            postSendEventConstructor = ::GroupMessagePostSendEvent,
          )

          assertNull(result, "pre-send 阶段失败应返回 null")
          assertEquals(1, postSendCalls.get(), "post-send 必须在 pre-send 失败分支被广播恰好一次")

          val event = captured.get()
          assertSame(group, event?.target)
          assertSame(body, event?.message, "失败分支 post-send.message 应为原始 body, 不是 listener 可能改写后的 chain")
          assertSame(boom, event?.exception, "post-send.exception 必须透传 pre-send 抛出的真实异常")
          assertNull(event?.receipt)
        } finally {
          // 清理监听器, 避免污染其他测试.
          listener.complete()
        }
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `pre-send 阶段 CancellationException 必须透传不能被 runCatching 吞掉`() {
    // 协作式取消契约: runCatching 默认会吃掉 CancellationException, 扩展到 post-send 两条路径后
    // 若不显式重抛, 调用方会误以为发送正常结束继续执行后续逻辑. 这条锁住重抛.
    val bot = StubBot()
    try {
      runBlocking {
        val group = GroupImpl(bot, bot.coroutineContext, "test-group-cancel")
        val ce = CancellationException("pre-send cancelled by parent")

        var caught: Throwable? = null
        try {
          group.callMessageOpenApi<Group>(
            endpoint = TencentEndpoint.PostGroupMessage,
            urlPlaceHolder = mapOf("group_openid" to group.id),
            body = PlainText("hi").toMessageChain(),
            messageSequence = 1,
            preSendEventConstructor = { _, _ -> throw ce },
            postSendEventConstructor = ::GroupMessagePostSendEvent,
          )
        } catch (t: Throwable) {
          caught = t
        }

        assertSame(ce, caught, "pre-send CancellationException 必须透传, 否则破坏协作式取消")
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `pre-send 成功但发送阶段失败时 post-send 带真实异常与改写后的 chain`() {
    val failure = IllegalStateException("send failure")
    val bot = StubBot(callOpenapiResult = Result.failure(failure))
    try {
      runBlocking {
        val group = GroupImpl(bot, bot.coroutineContext, "test-group-send-fail")

        val replaced = PlainText("replaced").toMessageChain()
        val preListener = GlobalEventChannel.subscribeAlways<GroupMessagePreSendEvent> { event ->
          if (event.target === group) {
            // listener 把消息体改写为 replaced chain; post-send 里应看到这个改写后的 chain.
            event.message = replaced
          }
        }

        val captured = AtomicReference<GroupMessagePostSendEvent?>(null)
        val postListener = GlobalEventChannel.subscribeAlways<GroupMessagePostSendEvent> { event ->
          if (event.target === group) {
            captured.set(event)
          }
        }

        try {
          val body = PlainText("original").toMessageChain()
          val result = group.callMessageOpenApi<Group>(
            endpoint = TencentEndpoint.PostGroupMessage,
            urlPlaceHolder = mapOf("group_openid" to group.id),
            body = body,
            messageSequence = 1,
            preSendEventConstructor = ::GroupMessagePreSendEvent,
            postSendEventConstructor = ::GroupMessagePostSendEvent,
          )

          assertNull(result, "callOpenapi 失败时 callMessageOpenApi 返回 null")
          val event = captured.get()
          assertSame(group, event?.target)
          assertEquals(
            expected = replaced.toString(),
            actual = event?.message?.toString(),
            message = "post-send.message 应为 listener 改写后的 chain",
          )
          assertSame(failure, event?.exception)
          assertNull(event?.receipt)
        } finally {
          preListener.complete()
          postListener.complete()
        }
      }
    } finally {
      bot.close()
    }
  }
}

private class StubBot(
  private val callOpenapiResult: Result<Any?> = Result.failure(IllegalStateException("callOpenapi not stubbed")),
) : TencentBot, CoroutineScope {
  override val id: String = "stub-bot"
  override val unionOpenid: String? = null
  override val bot: TencentBot
    get() = this
  // client/json 只用于实际 HTTP 调用; 在 2.5 覆盖的路径里 json 被 encodeToString 用到 (仅发送分支), 正常构造即可.
  override val client: HttpClient
    get() = error("StubBot.client must not be dereferenced in current test paths")
  override val json: Json = Json { ignoreUnknownKeys = true }
  override val logger: Logger = KtorSimpleLogger("stub-bot")
  override val eventChannel: EventChannel<TencentBotEvent> =
    GlobalEventChannel.filterIsInstance<TencentBotEvent>().filter { it.bot === this }
  override val guilds: ContactList<Guild> = GuildContactList { error("stub: guilds[$it] not provided") }
  override val groups: ContactList<Group> = GroupContactList { error("stub: groups[$it] not provided") }
  override val friends: ContactList<FriendUser> = SingleUserContactList { error("stub: friends[$it] not provided") }
  override val isPublic: Boolean = false
  override val isDebug: Boolean = false
  override val coroutineContext: CoroutineContext =
    SupervisorJob() + Dispatchers.Default + CoroutineName("StubBot.$id")

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<T> = callOpenapiResult as Result<T>

  override suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<Unit> = Result.failure(IllegalStateException("callOpenapi(Unit) not stubbed"))

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Contact>? = null
  override suspend fun uploadImage(url: String): TencentImage = error("unused")
  override suspend fun uploadImage(data: ByteArray): TencentImage = error("unused")

  override fun close() {
    coroutineContext[Job]?.cancel()
  }
}
