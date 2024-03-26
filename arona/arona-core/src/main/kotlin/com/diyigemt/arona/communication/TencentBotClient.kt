package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.communication.message.TencentWebsocketOperationManager.handleTencentOperation
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
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TencentBot : Contact, CoroutineScope {
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
private constructor(private val config: TencentBotConfig) : Closeable, TencentBot, CoroutineScope {
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
  override val coroutineContext: CoroutineContext = EmptyCoroutineContext + CoroutineName("Bot.${config.appId}")
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
  private lateinit var websocketContext: TencentBotClientWebSocketSession
  private val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", botToken)
    append("X-Union-Appid", id)
  }
  private val appId = config.appId
  private val botToken
    get() = "QQBot $accessToken"

  init {
    eventChannel.subscribeAlways<TencentBotWebsocketAuthSuccessEvent>(coroutineContext) {
      if (it.bot.id !== id) {
        return@subscribeAlways
      }
      // 此时bot正式登录成功, 开始维护websocket和token刷新长连接
      with(websocketContext) {
        startWebsocketHeartbeat()
      }
      // 获取频道列表
      if (isPublic) {
        Result.success(Unit)
      } else {
        fetchGuildList()
          .onFailure {
            logger.warn("get guild list failed")
          }
      }.onSuccess {
        // 通知插件登录成功
        BotManager.registerBot(this@TencentBotClient)
        TencentBotOnlineEvent(this@TencentBotClient).broadcast()
      }
    }
    eventChannel.subscribeAlways<TencentBotWebsocketConnectionResumeEvent>(coroutineContext) {
      if (it.bot.id !== id) {
        return@subscribeAlways
      }
      with(websocketContext) {
        startWebsocketHeartbeat()
      }
    }
  }

  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }

  fun auth() {
    eventChannel.subscribeOnce<TencentBotAuthSuccessEvent>(coroutineContext) {
      connectWs()
    }
    subscribeHandshake()
    // 处理断联事件
    eventChannel.subscribeAlways<TencentBotWebsocketConnectionLostEvent>(coroutineContext) {
      websocketHeartbeatTask?.cancel()
      // 发送resume操作
      // 保存上个context的信息
      val cache = runCatching {
        val cacheSessionId = websocketContext.sessionId
        val cacheSerialNumber = websocketContext.serialNumber
        cacheSessionId to cacheSerialNumber
      }.onFailure {
        // 重连失败，再走一遍登录流程
        subscribeHandshake()
        connectWs()
        return@subscribeAlways
      }.getOrNull() ?: return@subscribeAlways
      // 重新连接
      connectWs().onSuccess {
        delay(2000)
        with(websocketContext) {
          send(
            json.encodeToString(
              TencentWebsocketPayload(
                operation = TencentWebsocketOperationType.Resume,
                data = TencentWebsocketResumeReq(
                  token = botToken,
                  sessionId = cache.first,
                  serialNumber = cache.second
                )
              )
            )
          )
        }
        delay(2000)
        TencentBotWebsocketConnectionResumeEvent(this@TencentBotClient).broadcast()
      }
    }
    launch {
      updateAccessToken()
    }
  }

  private fun subscribeHandshake() {
    eventChannel.subscribeOnce<TencentBotWebsocketHandshakeSuccessEvent>(coroutineContext) {
      // 发送鉴权
      with(websocketContext) {
        accessTokenLock.withLock {
          send(
            json.encodeToString(
              TencentWebsocketPayload(
                operation = TencentWebsocketOperationType.Identify,
                data = TencentWebsocketIdentifyReq(
                  token = botToken,
                  intents = TencentMessageIntentsBuilder().apply {
                    if (this@TencentBotClient.isPublic) buildPublicBot()
                    else buildGuildBot()
                  }
                    .build(),
                  shard = listOf(0, 1)
                )
              )
            )
          )
        }
      }
    }
  }

  private suspend fun updateAccessToken() {
    accessTokenHeartbeatTask?.cancel("timeout")
    val resp = client.post("https://bots.qq.com/app/getAppAccessToken") {
      contentType(ContentType.Application.Json)
      setBody(Json.encodeToString(config.toAuthConfig()))
    }
    if (resp.status == HttpStatusCode.OK) {
      val data = Json.decodeFromString<TencentBotAuthEndpointResp>(resp.bodyAsText())
      accessTokenLock.withLock {
        accessToken = data.accessToken
      }
      with(this@TencentBotClient) {
        accessTokenHeartbeatTask = launch(SupervisorJob()) {
          delay(data.expiresIn * 1000L)
          updateAccessToken()
        }
      }
      TencentBotAuthSuccessEvent(this, data).broadcast()
    }
  }

  private suspend fun connectWs(): Result<TencentWebsocketEndpointResp> {
    return callOpenapi(TencentEndpoint.WebSocket, TencentWebsocketEndpointResp.serializer()) {
      method = HttpMethod.Get
    }.onSuccess {
      wsJob = with(this@TencentBotClient) {
        launch(SupervisorJob()) {
          client.ws({
            method = HttpMethod.Get
            url(it.url)
          }) {
            val cxt = this@TencentBotClient.toWebSocketSession(call, this)
            this@TencentBotClient.websocketContext = cxt
            while (cxt.handleTencentOperation()) {
              //
            }
          }
        }
      }
    }.onFailure {
      logger.error("get websocket endpoint failed.")
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
        if (guilds.delegate.none { exist -> exist.id == guild.id }) {
          guilds.delegate.add(
            GuildImpl(
              this,
              coroutineContext,
              guild
            )
          )
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
              base = it.replace("{$k}", v)
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
            "call endpoint failed, endpoint: {}, placeHolder: {}, body: {}",
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
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<*>? {
    TODO("Not yet implemented")
  }

  override suspend fun uploadImage(url: String): TencentImage {
    TODO("Not yet implemented")
  }

  override suspend fun uploadImage(data: ByteArray): TencentImage {
    TODO("Not yet implemented")
  }

  private fun TencentBotClientWebSocketSession.startWebsocketHeartbeat() {
    websocketHeartbeatTask?.cancel("timeout")
    websocketHeartbeatTask = launch(SupervisorJob()) {
      while (true) {
        sendApiData(
          TencentWebsocketPayload(
            operation = TencentWebsocketOperationType.Heartbeat,
            serialNumber = 0,
            type = TencentWebsocketEventType.NULL,
            data = if (serialNumber == 0L) null else serialNumber
          )
        )
        delay(heartbeatInterval)
      }
    }
  }

  override fun toString() = "TencentBot($appId)"

  override fun close() {
    wsJob?.cancel()
    websocketHeartbeatTask?.cancel()
    accessTokenHeartbeatTask?.cancel()
    client.close()
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
