package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.communication.message.TencentWebsocketOperationManager.handleTencentOperation
import com.diyigemt.arona.utils.runSuspend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.logging.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
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
  val users: ContactList<SingleUser>
  val isPublic: Boolean
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
  override val id = config.appId
  override val client = HttpClient(CIO) {
    install(WebSockets)
    install(ContentNegotiation) {
      json
    }
  }
  override val json = Json {
    ignoreUnknownKeys = true
  }

  override val logger = KtorSimpleLogger("Bot.$id")
  override val eventChannel =
    GlobalEventChannel.filterIsInstance<TencentBotEvent>().filter { it.bot === this@TencentBotClient }
  override val coroutineContext: CoroutineContext = EmptyCoroutineContext + CoroutineName("Bot.${config.appId}")
  override val guilds: ContactList<Guild> = GuildContactList { EmptyGuildImpl(this, it) }
  override val groups: ContactList<Group> = GroupContactList { EmptyGroupImpl(this, it) }
  override val users: ContactList<SingleUser> = SingleUserContactList { EmptySingleUserImpl(this, it) }
  override val isPublic = config.public
  private val timer = Timer("Bot.${config.appId}", true)
  private var accessToken: String = ""
  private lateinit var accessTokenHeartbeatTask: TimerTask
  private lateinit var websocketHeartbeatTask: TimerTask
  private lateinit var websocketContext: TencentBotClientWebSocketSession
  private val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", botToken)
    append("X-Union-Appid", id)
  }
  private val appId = config.appId
  private val botToken
    get() = "QQBot $accessToken"
  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }

  fun auth() = runSuspend {
    eventChannel.subscribeOnce<TencentBotAuthSuccessEvent>(coroutineContext) {
      logger.warn("TencentBotAuthSuccessEvent trigger")
      connectWs()
    }
    subscribeHandshake()
    eventChannel.subscribeOnce<TencentBotWebsocketAuthSuccessEvent>(coroutineContext) {
      logger.warn("TencentBotWebsocketAuthSuccessEvent trigger")
      // 此时bot正式登录成功, 开始维护websocket和token刷新长连接
      with(websocketContext) {
        startWebsocketHeartbeat()
      }
      // 获取频道列表
      fetchGuildList().onSuccess {
        // 通知插件端登录成功
        TencentBotOnlineEvent(this@TencentBotClient).broadcast()
      }
    }
    // 处理断联事件
    eventChannel.subscribeAlways<TencentBotWebsocketConnectionLostEvent>(coroutineContext) {
      websocketHeartbeatTask.cancel()
      // 发送resume操作
      // 保存上个context的信息
      val cache = runCatching {
        val cacheSessionId = websocketContext.sessionId
        val cacheSerialNumber = websocketContext.serialNumber
        cacheSessionId to cacheSerialNumber
      }.onFailure {
        // 重连失败，再走一遍登录流程
        subscribeHandshake()
        connectWs {
          it.startWebsocketHeartbeat()
        }
        return@subscribeAlways
      }.getOrNull() ?: return@subscribeAlways
      // 重新连接
      connectWs {
        with(it) {
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
        TencentBotWebsocketConnectionResumeEvent(it.bot).broadcast()
        it.startWebsocketHeartbeat()
      }
    }
    doAuth()
  }

  private fun subscribeHandshake() {
    eventChannel.subscribeOnce<TencentBotWebsocketHandshakeSuccessEvent>(coroutineContext) {
      // 发送鉴权
      with(websocketContext) {
        send(
          json.encodeToString(
            TencentWebsocketPayload(
              operation = TencentWebsocketOperationType.Identify,
              data = TencentWebsocketIdentifyReq(
                token = botToken,
                intents = TencentMessageIntentsBuilder().apply {
                  if (this@TencentBotClient.isPublic) buildPublicBot()
                  else append(TencentMessageIntentSuperType.DIRECT_MESSAGE)
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

  private suspend fun doAuth() {
    val resp = client.post("https://bots.qq.com/app/getAppAccessToken") {
      contentType(ContentType.Application.Json)
      setBody(Json.encodeToString(config.toAuthConfig()))
    }
    if (resp.status == HttpStatusCode.OK) {
      val authResult = Json.decodeFromString<TencentBotAuthEndpointResp>(resp.bodyAsText())
      startAccessTokenHeartbeat(authResult.expiresIn)
      accessToken = authResult.accessToken
      TencentBotAuthSuccessEvent(this, authResult).broadcast()
    } else {
      logger.error("request app token failed")
    }
  }

  private suspend fun connectWs(onConnected: suspend (TencentBotClientWebSocketSession) -> Unit = {}) {
    callOpenapi(TencentEndpoint.WebSocket, TencentWebsocketEndpointResp.serializer()) {
      method = HttpMethod.Get
    }.onSuccess {
      client.ws({
        method = HttpMethod.Get
        url(it.url)
        headers(openapiSignHeader)
      }) {
        val cxt = this@TencentBotClient.toWebSocketSession(call, this)
        this@TencentBotClient.websocketContext = cxt
        runCatching {
          onConnected(cxt)
        }.onFailure {
          logger.error(it)
        }
        while (cxt.handleTencentOperation()) {
          //
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
      guilds.delegate.addAll(
        it.map { guild ->
          GuildImpl(
            this,
            coroutineContext,
            guild
          )
        }
      )
    }
  }

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
        headers(openapiSignHeader).apply {
//          when (endpoint) {
//            TencentEndpoint.PostGuildMessage, TencentEndpoint.PostGroupMessage -> {}
//            else -> {
//              remove("Authorization")
//              append("Authorization", "Bot ${config.appId}.${config.token}")
//            }
//          }
        }
        contentType(ContentType.Application.Json)
        url(
          "https://api.sgroup.qq.com${endpoint.path}".let {
            var base = it
            urlPlaceHolder.forEach { (k, v) ->
              base = it.replace("{$k}", v)
            }
            base
          }
        )
        block.invoke(this)
      }
      if (resp.status == HttpStatusCode.OK) {
        bodyTmp = resp.bodyAsText()
        json.decodeFromString(decoder, bodyTmp)
      } else {
        throw HttpNotOkException(resp.status, resp.bodyAsText(), resp.headers["X-Tps-Trace-Id"])
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
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    TODO("Not yet implemented")
  }

  private fun TencentBotClientWebSocketSession.startWebsocketHeartbeat() =
    timer.scheduleAtFixedRate(0, heartbeatInterval) {
      runSuspend {
        sendApiData(
          TencentWebsocketPayload(
            operation = TencentWebsocketOperationType.Heartbeat,
            serialNumber = 0,
            type = TencentWebsocketEventType.NULL,
            data = if (serialNumber == 0L) null else serialNumber
          )
        )
      }
    }.also { websocketHeartbeatTask = it }


  private fun startAccessTokenHeartbeat(interval: Int) {
    timer.schedule(interval * 1000L) {
      runSuspend {
        doAuth()
      }
    }.also { accessTokenHeartbeatTask = it }
  }

  override fun toString() = "TencentBot($appId)"

  override fun close() {
    websocketHeartbeatTask.cancel()
    accessTokenHeartbeatTask.cancel()
    timer.cancel()
    client.close()
  }
}

class HttpNotOkException(status: HttpStatusCode, body: String, traceId: String? = "") : Exception(
  "status: $status, traceId: $traceId, message: $body"
)
