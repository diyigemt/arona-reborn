package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentBotContactCacheConfig
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.event.EventChannel
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.TencentBotEvent
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageReceipt
import com.diyigemt.arona.communication.message.TencentImage
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import kotlin.coroutines.CoroutineContext

/**
 * [StubBot] 记录下来的一次 openapi 调用.
 *
 * [request] 是执行完调用方 block 后的 builder, 因此 method 与 `url.parameters` 可直接断言——
 * 路由类缺陷 (端点选错、占位符 key 写反、query 漏带) 只有在这一层才看得见.
 */
internal data class StubOpenapiCall(
  val endpoint: TencentEndpoint,
  val placeholders: Map<String, String>,
  val request: HttpRequestBuilder,
)

/**
 * 可复用的 TencentBot 测试替身:
 *
 * - `callOpenapi` 固定返回 [callOpenapiResult] / [unitCallOpenapiResult], 便于断言各路径;
 * - 每次调用都会执行调用方 block 并记入 [calls], 不出网;
 * - `guilds/groups/friends` 默认走 Empty* 占位工厂, 和生产代码里的 TencentBotClient 结构一致;
 * - `client` 没有被任何当前测试路径真实触达, getter 直接 error, 避免隐性走真实 HTTP;
 * - `coroutineContext` 带 SupervisorJob, 测试结束 [close] 统一 cancel, 避免协程泄漏影响其他 testsuite.
 */
internal class StubBot(
  private val callOpenapiResult: Result<Any?> = Result.failure(IllegalStateException("callOpenapi not stubbed")),
  private val unitCallOpenapiResult: Result<Unit> =
    Result.failure(IllegalStateException("callOpenapi(Unit) not stubbed")),
  override val isPublic: Boolean = false,
) : TencentBot, CoroutineScope {
  private val recordedCalls = mutableListOf<StubOpenapiCall>()

  /**
   * 按调用顺序记录的 openapi 调用, 只含请求构造成功的那些.
   */
  val calls: List<StubOpenapiCall> get() = recordedCalls

  /**
   * 进入 `callOpenapi` 的次数, 在执行调用方 block 之前就自增.
   *
   * 与 [calls] 分开是因为 block 抛异常时调用其实已经发生, 只是没留下可断言的 builder——
   * 断言"本地前置校验拦住了, 压根没进 openapi"必须看这个计数, 否则区分不出这两种情况.
   */
  var attempts: Int = 0
    private set
  override val id: String = "stub-bot"
  override val unionOpenid: String? = null
  override val bot: TencentBot
    get() = this
  override val client: HttpClient
    get() = error("StubBot.client must not be dereferenced in current test paths")
  override val json: Json = Json { ignoreUnknownKeys = true }
  override val logger: Logger = KtorSimpleLogger("stub-bot")
  override val eventChannel: EventChannel<TencentBotEvent> =
    GlobalEventChannel.filterIsInstance<TencentBotEvent>().filter { it.bot === this }
  override val guilds: ContactList<Guild> = GuildContactList { EmptyGuildImpl(this@StubBot, it) }
  override val groups: ContactList<Group> = GroupContactList { EmptyGroupImpl(this@StubBot, it) }
  override val friends: ContactList<FriendUser> = SingleUserContactList { EmptyFriendUserImpl(this@StubBot, it) }
  override val isDebug: Boolean = false
  override val isShadow: Boolean = false
  override val contactCache: TencentBotContactCacheConfig = TencentBotContactCacheConfig()
  override val coroutineContext: CoroutineContext =
    SupervisorJob() + Dispatchers.Default + CoroutineName("StubBot.$id")

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<T> = record(endpoint, urlPlaceHolder, block).flatMap { callOpenapiResult as Result<T> }

  override suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<Unit> = record(endpoint, urlPlaceHolder, block).flatMap { unitCallOpenapiResult }

  /**
   * 在一次性 builder 上执行 block 并留痕, 全程不触碰 HttpClient——与真实 client 的 shadow 分支同构.
   * block 自身抛异常时按真实实现的语义转成 failure.
   */
  private fun record(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<Unit> = runCatching {
    attempts += 1
    val request = HttpRequestBuilder().apply(block)
    recordedCalls += StubOpenapiCall(endpoint, urlPlaceHolder.toMap(), request)
  }

  private fun <T> Result<Unit>.flatMap(next: () -> Result<T>): Result<T> =
    fold(onSuccess = { next() }, onFailure = { Result.failure(it) })

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Contact>? = null
  override suspend fun uploadImage(url: String): TencentImage = error("unused")
  override suspend fun uploadImage(data: ByteArray): TencentImage = error("unused")

  override fun close() {
    coroutineContext[Job]?.cancel()
  }
}
