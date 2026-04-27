package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

interface TencentBot : Closeable, Contact, CoroutineScope {
  val client: HttpClient
  val json: Json
  val logger: Logger
  val eventChannel: EventChannel<TencentBotEvent>
  override val id: String
  val guilds: ContactList<Guild>
  val groups: ContactList<Group>
  val friends: ContactList<FriendUser>
  val isPublic: Boolean
  val isDebug: Boolean
  suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String> = mapOf(),
    block: HttpRequestBuilder.() -> Unit,
  ): Result<T>

  suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String> = mapOf(),
    block: HttpRequestBuilder.() -> Unit,
  ): Result<Unit>
}

internal class TencentBotClient
private constructor(private val config: TencentBotConfig) : 
  WebhookBot(config.secret), TencentBot, CoroutineScope {
  override val id = config.id
  override val json = Json {
    ignoreUnknownKeys = true
  }
  override val client = HttpClient(CIO) {
    install(ContentNegotiation) {
      json(json)
    }
  }
  override val logger = KtorSimpleLogger("Bot.$id")
  override val eventChannel =
    GlobalEventChannel.filterIsInstance<TencentBotEvent>().filter { it.bot === this@TencentBotClient }
  // SupervisorJob 让 token 刷新等子任务异常不会传染整个 bot; bot.close() 通过 cancel() 收尾.
  override val coroutineContext: CoroutineContext =
    SupervisorJob() + Dispatchers.IO + CoroutineName("Bot.${config.appId}")
  override val guilds: ContactList<Guild> = GuildContactList { EmptyGuildImpl(this, it) }
  override val groups: ContactList<Group> = GroupContactList { EmptyGroupImpl(this, it) }
  override val friends: ContactList<FriendUser> = SingleUserContactList { EmptyFriendUserImpl(this, it) }
  override val isPublic = config.public
  override val isDebug = config.debug
  // Sprint 3 后续: token 刷新合流到 TokenRefresher, 它内部用 AtomicReference<Job?> 做 single-flight gate
  // + AtomicReference<TokenSnapshot> 让 (token, version) 作为单原子读写, 消掉 stale 401 误判和并发刷新风暴.
  // 旧实现 (cancel + launch 重启长 while-loop) 在并发 401 下会撞车, 详见 TokenRefresher.
  private val tokenRefresher = TokenRefresher(
    scope = this,
    logger = logger,
    fetchAccessToken = ::getAccessToken,
    onRefreshSuccess = { data ->
      TencentBotAuthSuccessEvent(this@TencentBotClient, data).broadcast()
    },
    onFatal = { this@TencentBotClient.close() },
  )
  private val appId = config.appId

  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }

  fun auth() {
    eventChannel.subscribeOnce<TencentBotAuthSuccessEvent>(coroutineContext) {
      // 获取频道列表
      if (isPublic) {
        Result.success(Unit)
      } else {
        fetchGuildList()
          .onFailure {
            logger.warn("get guild list failed")
          }
      }
      // 通知插件登录成功
      BotManager.registerBot(this@TencentBotClient)
      TencentBotOnlineEvent(this@TencentBotClient).broadcast()
    }
    // 请求token
    tokenRefresher.triggerRefresh()
  }

  private suspend fun getAccessToken(): TencentBotAuthEndpointResp? {
    val resp = client.post("https://bots.qq.com/app/getAppAccessToken") {
      contentType(ContentType.Application.Json)
      setBody(Json.encodeToString(config.toAuthConfig()))
    }
    return if (resp.status == HttpStatusCode.OK) {
      kotlin.runCatching {
        Json.decodeFromString<TencentBotAuthEndpointResp>(resp.bodyAsText())
      }.getOrNull()
    } else {
      null
    }
  }

  fun dispatchWebhookEvent(source: String) {
    this.launch {
      // Sprint 2.1: 不再通过 fakeWebSocketSession + 反射桥接, 直接解 Payload0 拿 event type, 走多态 dispatch.
      val preData = runCatching {
        json.decodeFromString<TencentWebsocketPayload0>(source)
      }.getOrElse {
        logger.error("failed to decode webhook payload envelope: $source", it)
        return@launch
      }
      TencentWebsocketDispatchEventManager.handleTencentDispatchEvent(
        TencentDispatchContext(this@TencentBotClient),
        preData.type,
        source,
      )
    }
  }

  /**
   * 获取加入的频道列表
   */
  private suspend fun fetchGuildList(): Result<List<TencentGuildRaw>> {
    return callOpenapi(TencentEndpoint.GetBotGuildList, ListSerializer(TencentGuildRaw.serializer())) {
      method = HttpMethod.Get
      contentType(ContentType.Application.Json)
    }.onSuccess {
      it.forEach { guild ->
        guilds.getOrCreate(guild.id) {
          GuildImpl(this, coroutineContext, guild)
        }
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  @OptIn(ExperimentalSerializationApi::class)
  override suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<T> {
    var bodyTmp = ""
    // 一次性原子读 (token, version) 快照: 避免分两次读到 (旧 version, 新 token) 的从未真实存在配对,
    // 否则 401 时 stale 检查会误判, 让真实失效的 token 反复 401 直到下一次 heartbeat 才解.
    val requestSnapshot = tokenRefresher.current()
    return runCatching {
      val resp = client.request {
        contentType(ContentType.Application.Json)
        headers {
          append("Authorization", "QQBot ${requestSnapshot.token}")
          append("X-Union-Appid", id)
        }
        url(
          "https://${if (isDebug) "sandbox." else ""}api.sgroup.qq.com${endpoint.path}".let {
            var base = it
            urlPlaceHolder.forEach { (k, v) ->
              base = base.replace("{$k}", v)
            }
            base
          }
        )
        block.invoke(this)
      }
      if (isDebug) {
        logger.info("sending message, endpoint: {}, body: {}", endpoint, resp.request.content.toString())
      }
      if (resp.status == HttpStatusCode.OK) {
        bodyTmp = resp.bodyAsText()
        return@runCatching if (decoder == Unit.serializer()) {
          Unit as T
        } else json.decodeFromString(decoder, bodyTmp)
      } else {
        bodyTmp = resp.bodyAsText()
        if (bodyTmp.contains("\"code\":22009")) {
          throw ImageFailedException(resp.status, bodyTmp)
        } else if (bodyTmp.contains("\"code\":40054005")) {
          throw MessageDuplicationException(resp.status, bodyTmp)
        } else if (bodyTmp.contains("\"code\":11244")) {
          throw ApiTokenExpiredException(resp.status, bodyTmp)
        }
        throw HttpNotOkException(
          resp.status,
          bodyTmp,
          resp.headers["X-Tps-Trace-Id"] ?: "",
          resp.request.content.toString()
        )
      }
    }.onFailure {
      when (it) {
        is MissingFieldException -> {
          logger.error(
            "call endpoint failed, missing file, endpoint: {}, placeHolder: {}, body: {}",
            endpoint, urlPlaceHolder, bodyTmp
          )
        }

        is HttpNotOkException -> {
          logger.error(
            "call endpoint failed, endpoint: {}, placeHolder: {}, body: {}",
            endpoint, urlPlaceHolder, it.message
          )
        }

        is ImageFailedException -> {
          logger.error(
            "call endpoint failed, endpoint: {}, placeHolder: {}, cause: msg limit exceed",
            endpoint, urlPlaceHolder
          )
        }
        
        is ApiTokenExpiredException -> {
          // requestSnapshot.version 携带"我用的是哪一代 token". 如果此刻 refresher 已经推进到更新代,
          // 这次 401 只是旧请求尾流, 不应再触发刷新; TokenRefresher 内部 single-flight gate 也兜底合流.
          tokenRefresher.triggerRefresh(requestSnapshot.version)
        }

        else -> logger.error(it)
      }
    }
  }

  override suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit,
  ) = callOpenapi(endpoint, Unit.serializer(), urlPlaceHolder, block)

  override val bot: TencentBot = this
  override val unionOpenid: String = config.appId
  // TencentBot 是 Guild/Group/Friend 的聚合体, 本身不是任何消息的真实接收方;
  // 这三个 Contact 接口方法只是继承下来不得不实现的壳, 直接调用是 API 误用.
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<*>? {
    throw UnsupportedOperationException("TencentBot is not a message receiver. Send on a concrete Group/Friend/Channel contact instead.")
  }

  override suspend fun uploadImage(url: String): TencentImage {
    throw UnsupportedOperationException("TencentBot is not a message receiver. Upload image via a concrete Group/Friend contact instead.")
  }

  override suspend fun uploadImage(data: ByteArray): TencentImage {
    throw UnsupportedOperationException("TencentBot is not a message receiver. Upload image via a concrete Group/Friend contact instead.")
  }

  override fun toString() = "TencentBot($appId)"

  override fun close() {
    // Sprint 2.1 Part B: WS lifecycle jobs 随 WS 死代码下线; 现在只剩 token refresher + HTTP client + bot scope.
    tokenRefresher.close()
    client.close()
    // 同步取消整个 bot scope, 防止 close 后仍有人 launch 出新的子任务.
    coroutineContext.cancel()
  }
}

// (token, version) 必须作为单原子单元一起更新/读取. 拆成 @Volatile token + AtomicLong version 看似等价,
// 实则会让外层读到 (旧 version + 新 token) 这种写入侧从未真实存在的配对, 进而把真实 401 误判成 stale.
internal data class TokenSnapshot(val token: String, val version: Long) {
  companion object {
    val Empty = TokenSnapshot("", 0L)
  }
}

// 把 token 刷新孤立成独立协作者, 收口三件事:
//  1. single-flight: AtomicReference<Job?> CAS, 多触发并发合流为 1 次 HTTP refresh.
//  2. stale 401 抑制: 调用方传入 requestVersion, 与当前 snapshot.version 不一致直接 return.
//  3. 心跳: 不再常驻 while(true), 改为 "refresh 成功 → schedule 下一次 heartbeat job", 生命周期有限.
// 重试预算 (5s/15s/45s) 统一在单次 refresh 内消耗, 不分触发来源, 避免 401 风暴叠加预算.
// 默认 backoff: 失败间隔 5s/15s/45s, 共最多 3 次尝试. List 而非 LongArray, 避免被外部意外 mutate.
// 测试可注入 short backoff, 与 GuildInitRetry (Sprint 3.5c) 模式一致.
internal val DefaultTokenRefreshBackoff: List<Long> = listOf(5_000L, 15_000L, 45_000L)

internal class TokenRefresher(
  private val scope: CoroutineScope,
  private val logger: Logger,
  private val fetchAccessToken: suspend () -> TencentBotAuthEndpointResp?,
  private val onRefreshSuccess: suspend (TencentBotAuthEndpointResp) -> Unit,
  private val onFatal: () -> Unit,
  private val retryBackoff: List<Long> = DefaultTokenRefreshBackoff,
) {
  init {
    require(retryBackoff.isNotEmpty()) { "retryBackoff must contain at least one attempt budget" }
  }

  private val snapshot = AtomicReference(TokenSnapshot.Empty)
  private val refreshInFlight = AtomicReference<Job?>(null)
  private val scheduledHeartbeat = AtomicReference<Job?>(null)
  private val closed = AtomicBoolean(false)

  fun current(): TokenSnapshot = snapshot.get()

  fun triggerRefresh(requestVersion: Long? = null) {
    if (closed.get()) return
    if (requestVersion != null && requestVersion != snapshot.get().version) return

    // 必须 LAZY: single-flight gate 的不变量是 "进入 gate 后 body 才被允许开跑".
    // EAGER 启动会让 body 在 CAS refreshInFlight 之前可能就开始执行甚至跑完, 期间
    // 别的并发 triggerRefresh 看到 refreshInFlight==null 会以为没人在刷, 又启动一份, 单飞失效.
    // LAZY + 先 CAS 入 gate + 后 start() 锁住 "进 gate → 才能 run body" 这个时序.
    // (invokeOnCompletion 对已完成 job 会立即同步触发 handler, 注册顺序不影响 handler 不丢.)
    val refreshJob = scope.launch(CoroutineName("BotTokenRefresh"), start = CoroutineStart.LAZY) {
      val data = runRefreshWithRetry()
      if (data == null) {
        logger.error("get access token failed after ${retryBackoff.size} attempts, aborting bot.")
        onFatal()
        return@launch
      }
      snapshot.updateAndGet { TokenSnapshot(data.accessToken, it.version + 1) }
      onRefreshSuccess(data)
      scheduleNextHeartbeat(data.expiresIn)
    }

    if (!refreshInFlight.compareAndSet(null, refreshJob)) {
      // 已有 in-flight refresh: 把这个未启动的 LAZY job 丢弃, 保持 fire-and-forget 语义.
      refreshJob.cancel()
      return
    }
    refreshJob.invokeOnCompletion { refreshInFlight.compareAndSet(refreshJob, null) }
    refreshJob.start()
  }

  private suspend fun runRefreshWithRetry(): TencentBotAuthEndpointResp? {
    for ((index, backoff) in retryBackoff.withIndex()) {
      val data = try {
        fetchAccessToken()
      } catch (ce: CancellationException) {
        // 外部 close()/scope cancel 必须直通, 不能被当成"刷新失败一次".
        throw ce
      } catch (t: Throwable) {
        // fetch 抛异常 (网络错/反序列化失败) 旧实现会让 launch 静默死亡, 后续既无 heartbeat 也无 fatal.
        // 这里转成 null 计入预算, 走完才 onFatal, 与 GuildInitRetry (Sprint 3.5c) 模式对齐.
        logger.warn("fetch access token failed on attempt ${index + 1}", t)
        null
      }
      if (data != null) return data
      if (index < retryBackoff.lastIndex) delay(backoff)
    }
    return null
  }

  private fun scheduleNextHeartbeat(expiresIn: Int) {
    if (closed.get()) return

    // 距离过期至少留 30s 缓冲, 与原实现对齐.
    val delayMillis = (expiresIn - 30).coerceAtLeast(1) * 1000L
    val nextHeartbeat = scope.launch(CoroutineName("BotTokenHeartbeat"), start = CoroutineStart.LAZY) {
      delay(delayMillis)
      triggerRefresh()
    }

    val previous = scheduledHeartbeat.getAndSet(nextHeartbeat)
    previous?.cancel()

    // race 2: close() 可能在 getAndSet 之后 / start 之前发生. close() 的 getAndSet 会拿到 nextHeartbeat
    // 并 cancel 它; 这里再 double-check closed, 抢回未启动的 nextHeartbeat 防止泄漏. cancel 一个未 start
    // 的 LAZY job 是安全的, 之后 start() 也只会立即触发 completion 而不跑 body.
    if (closed.get()) {
      if (scheduledHeartbeat.compareAndSet(nextHeartbeat, null)) {
        nextHeartbeat.cancel()
      }
      return
    }
    nextHeartbeat.start()
  }

  fun close() {
    if (!closed.compareAndSet(false, true)) return
    scheduledHeartbeat.getAndSet(null)?.cancel()
    refreshInFlight.getAndSet(null)?.cancel()
  }
}

@Serializable
data class TencentApiErrorResp(
  val message: String,
  val code: Int,
  @SerialName("trace_id")
  val traceId: String = "",
) {
  override fun toString(): String {
    return "traceId: ${traceId}, message: ${message}, code: ${code}"
  }
}

sealed class TencentApiErrorException(
  val status: HttpStatusCode,
  val source: TencentApiErrorResp,
  val req: String? = "",
) : Exception(source.toString())

class HttpNotOkException(status: HttpStatusCode, body: String, traceId: String = "", req: String? = "") :
  TencentApiErrorException(
    status, JsonIgnoreUnknownKeys.decodeFromString(body), req
  ) {
  override val message: String = "status: $status, $source, req-body: $req"
}

class ImageFailedException(status: HttpStatusCode, body: String) : TencentApiErrorException(
  status, JsonIgnoreUnknownKeys.decodeFromString(body)
) {
  override val message: String = "image upload failed, $source"
}

class ImageDownloadFailedException(status: HttpStatusCode, body: String) : TencentApiErrorException(
  status, JsonIgnoreUnknownKeys.decodeFromString(body)
) {
  override val message: String = "image download failed, $source"
}

class MessageDuplicationException(status: HttpStatusCode, body: String) : TencentApiErrorException(
  status, JsonIgnoreUnknownKeys.decodeFromString(body)
) {
  override val message: String = "message duplicated, ${source.message}"
}

class ApiTokenExpiredException(status: HttpStatusCode, body: String) : TencentApiErrorException(
  status, JsonIgnoreUnknownKeys.decodeFromString(body)
) {
  override val message: String = "token has expired, ${source.message}"
}
