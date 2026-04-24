package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.logging.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
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
    install(WebSockets)
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
  private val accessTokenLock = Mutex()
  private var accessToken: String = ""
  private var accessTokenHeartbeatTask: Job? = null
  private var websocketHeartbeatTask: Job? = null
  private var wsJob: Job? = null
  private val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", botToken)
    append("X-Union-Appid", id)
  }
  private val appId = config.appId
  private val botToken
    get() = "QQBot $accessToken"
  private val fakeHttpClientCall = FakeHttpClientCall(client)
  private val fakeWebsocket = FakeWebsocket(coroutineContext)
  private val fakeWebSocketSession = toWebSocketSession(fakeHttpClientCall, fakeWebsocket)

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
    updateAccessToken()
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

  private fun updateAccessToken() {
    accessTokenHeartbeatTask?.cancel("timeout")
    accessTokenHeartbeatTask = launch(CoroutineName("BotTokenRefresh")) {
      // 失败连续 3 次后真正退出循环并 close 整个 bot, 避免无限 delay 浪费协程.
      // 失败间隔走指数退避, 防止瞬间打爆腾讯接口.
      val retryBackoff = longArrayOf(5_000L, 15_000L, 45_000L)
      var retryCount = 0
      while (true) {
        if (retryCount >= retryBackoff.size) {
          logger.error("get access token failed after ${retryBackoff.size} retries, aborting bot.")
          this@TencentBotClient.close()
          return@launch
        }
        val data = getAccessToken()
        if (data == null) {
          delay(retryBackoff[retryCount])
          retryCount++
          continue
        }
        retryCount = 0
        accessTokenLock.withLock {
          accessToken = data.accessToken
        }
        TencentBotAuthSuccessEvent(this@TencentBotClient, data).broadcast()
        // 快过期的 60s 内申请会刷新 token; 距离过期至少留 30s 缓冲.
        delay((data.expiresIn - 30).coerceAtLeast(1) * 1000L)
      }
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
    return runCatching {
      val resp = client.request {
        accessTokenLock.withLock {
          headers(openapiSignHeader)
        }
        contentType(ContentType.Application.Json)
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
          updateAccessToken()
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
    wsJob?.cancel()
    websocketHeartbeatTask?.cancel()
    accessTokenHeartbeatTask?.cancel()
    client.close()
    // 同步取消整个 bot scope, 防止 close 后仍有人 launch 出新的子任务.
    coroutineContext.cancel()
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
