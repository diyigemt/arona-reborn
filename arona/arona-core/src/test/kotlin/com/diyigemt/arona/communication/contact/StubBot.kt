package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
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
 * 可复用的 TencentBot 测试替身:
 *
 * - `callOpenapi` 固定返回 [callOpenapiResult], 便于断言 send 成功/失败各路径;
 * - `guilds/groups/friends` 默认走 Empty* 占位工厂, 和生产代码里的 TencentBotClient 结构一致;
 * - `client` 没有被任何当前测试路径真实触达, getter 直接 error, 避免隐性走真实 HTTP;
 * - `coroutineContext` 带 SupervisorJob, 测试结束 [close] 统一 cancel, 避免协程泄漏影响其他 testsuite.
 */
internal class StubBot(
  private val callOpenapiResult: Result<Any?> = Result.failure(IllegalStateException("callOpenapi not stubbed")),
) : TencentBot, CoroutineScope {
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
