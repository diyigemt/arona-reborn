package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.contact.ContactList
import com.diyigemt.arona.communication.contact.Group
import com.diyigemt.arona.communication.contact.Guild
import com.diyigemt.arona.communication.contact.SingleUser
import com.diyigemt.arona.communication.message.TencentWebsocketOperationManager.handleTencentOperation
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.event.TencentBotWebsocketAuthSuccessEvent
import com.diyigemt.arona.communication.event.TencentBotWebsocketHandshakeSuccessEvent
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.communication.message.TencentBotClientWebSocketSession
import com.diyigemt.arona.communication.message.TencentMessageIntentSuperType
import com.diyigemt.arona.communication.message.TencentMessageIntentsBuilder
import com.diyigemt.arona.communication.message.TencentWebsocketIdentifyReq
import com.diyigemt.arona.communication.message.TencentWebsocketOperationType
import com.diyigemt.arona.communication.message.TencentWebsocketPayload
import com.diyigemt.arona.utils.runSuspend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.logging.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface TencentBot : CoroutineScope {
  val client: HttpClient
  val json: Json
  val logger: Logger
  val eventChannel: EventChannel<TencentEvent>
  val id: String
  val guilds: ContactList<Guild>
  val groups: ContactList<Group>
  val friends: ContactList<SingleUser>
  suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String> = mapOf(),
    block: HttpRequestBuilder.() -> Unit
  ): Result<T>

  suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String> = mapOf(),
    block: HttpRequestBuilder.() -> Unit
  ): Result<Unit>
}

internal class TencentBotClient private constructor(private val config: TencentBotConfig) : Closeable, TencentBot,
  CoroutineScope {
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
    GlobalEventChannel.filterIsInstance<TencentEvent>().filter { it.bot === this@TencentBotClient }
  override val coroutineContext: CoroutineContext = EmptyCoroutineContext + CoroutineName("Bot.${config.appId}")
  override val guilds: ContactList<Guild> = ContactList()
  override val groups: ContactList<Group> = ContactList()
  override val friends: ContactList<SingleUser> = ContactList()

  private val timer = Timer("Bot.${config.appId}", true)
  private var accessToken: String = ""
  private lateinit var accessTokenHeartbeatTask: TimerTask
  private lateinit var websocketHeartbeatTask: TimerTask
  private lateinit var websocketContext: TencentBotClientWebSocketSession
  private val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", botToken)
    append("X-Union-Appid", id)
  }
  private val botToken
    get() = "QQBot $accessToken"

  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }

  fun auth() = runSuspend {
    eventChannel.subscribeOnce<TencentBotWebsocketHandshakeSuccessEvent>(coroutineContext) {
      // 发送鉴权
      with(websocketContext) {
        send(
          json.encodeToString(
            TencentWebsocketPayload(
              operation = TencentWebsocketOperationType.Identify,
              data = TencentWebsocketIdentifyReq(
                token = botToken,
                intents = TencentMessageIntentsBuilder()
                  .append(TencentMessageIntentSuperType.GUILD_MESSAGES)
                  .build(),
                shard = listOf(0, 1)
              )
            )
          )
        )
      }
    }
    eventChannel.subscribeOnce<TencentBotWebsocketAuthSuccessEvent>(coroutineContext) {
      // 此时bot正式登录成功, 开始维护websocket和token刷新长连接
      with(websocketContext) {
        startWebsocketHeartbeat()
      }
      // 通知插件端登录成功
      TencentBotOnlineEvent(this@TencentBotClient).broadcast()
    }
    doAuth()
    connectWs()
  }

  private suspend fun doAuth() {
    val resp = client.post("https://bots.qq.com/app/getAppAccessToken") {
      contentType(ContentType.Application.Json)
      setBody(Json.encodeToString(config.toAuthConfig()))
    }
    if (resp.status == HttpStatusCode.OK) {
      val authResult = Json.decodeFromString<TencentBotAuthEndpointResp>(resp.bodyAsText())
      if (accessToken.isEmpty()) {
        startAccessTokenHeartbeat()
      }
      accessToken = authResult.accessToken
    } else {
      throw Exception()
    }
  }

  private suspend fun connectWs() {
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
        while (true) {
          cxt.handleTencentOperation()
        }
      }
    }.onFailure {
      logger.error("get websocket endpoint failed.")
    }
  }

  override suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit
  ) =
    runCatching {
      val resp = client.request {
        headers(openapiSignHeader).apply {
          // TODO 删掉兼容用的header
          if (endpoint != TencentEndpoint.WebSocket) {
            remove("Authorization")
            append("Authorization", "Bot ${config.appId}.${config.token}")
          }
        }
        contentType(ContentType.Application.Json)
        url(
          "https://sandbox.api.sgroup.qq.com${endpoint.path}".let {
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
        json.decodeFromString(decoder, resp.bodyAsText())
      } else {
        throw Exception(resp.bodyAsText())
      }
    }

  override suspend fun callOpenapi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String>,
    block: HttpRequestBuilder.() -> Unit
  ) = callOpenapi(endpoint, Unit.serializer(), urlPlaceHolder, block)

  private fun TencentBotClientWebSocketSession.startWebsocketHeartbeat() {
    (timer.scheduleAtFixedRate(0, heartbeatInterval) {
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
    }).also { websocketHeartbeatTask = it }
  }

  private fun startAccessTokenHeartbeat() =
    (timer.scheduleAtFixedRate((7200L - 30L) * 1000, (7200L - 30L) * 1000) {
      runSuspend { doAuth() }
    }).also { accessTokenHeartbeatTask = it }

  override fun close() {
    websocketHeartbeatTask.cancel()
    accessTokenHeartbeatTask.cancel()
    timer.cancel()
    client.close()
  }
}
