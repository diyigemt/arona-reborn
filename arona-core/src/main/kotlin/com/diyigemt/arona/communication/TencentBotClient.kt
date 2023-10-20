package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.TencentWebsocketOperationManager.handleTencentOperation
import com.diyigemt.arona.utils.runSuspend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.logging.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

internal class TencentBotClientConnectionMaintainer
  (
  private val bot: TencentBotClient,
  private val client: HttpClient
) : Closeable {
  private val timer = Timer("", true)
  private var accessToken: String = ""
  private lateinit var accessTokenHeartbeatTask: TimerTask
  private lateinit var websocketHeartbeatTask: TimerTask
  val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", botToken)
    append("X-Union-Appid", bot.config.appId)
  }
  val botToken
    get() = "QQBot $accessToken"

  suspend fun auth() {
    val resp = client.post("https://bots.qq.com/app/getAppAccessToken") {
      contentType(ContentType.Application.Json)
      setBody(Json.encodeToString(bot.config.toAuthConfig()))
    }
    if (resp.status == HttpStatusCode.OK) {
      val authResult = Json.decodeFromString<TencentBotAuthEndpointResp>(resp.bodyAsText())
      accessToken = authResult.accessToken
      startAccessTokenHeartbeat()
    } else {
      throw Exception()
    }
  }

  fun startWebsocketHeartbeat(interval: Long, block: suspend () -> Unit) {
    (timer.scheduleAtFixedRate(0, interval) {
      runSuspend {
        block()
      }
    }).also { websocketHeartbeatTask = it }
  }

  private fun startAccessTokenHeartbeat() =
    (timer.scheduleAtFixedRate((7200L - 30L) * 1000, (7200L - 30L) * 1000) {
      runSuspend { auth() }
    }).also { accessTokenHeartbeatTask = it }

  override fun close() {
    accessTokenHeartbeatTask.cancel()
  }
}

internal interface TencentBot {
  val client: HttpClient
  val json: Json
  val connectionMaintainer: TencentBotClientConnectionMaintainer
  val openapiSignHeader: HeadersBuilder.() -> Unit
  val logger: Logger
}

internal open class TencentBotClient private constructor(val config: TencentBotConfig) : Closeable, TencentBot {
  override val client = HttpClient(CIO) {
    install(WebSockets)
  }
  override val json = Json {
    ignoreUnknownKeys = true
  }
  final override val connectionMaintainer by lazy {
    TencentBotClientConnectionMaintainer(this, client)
  }
  override val openapiSignHeader = connectionMaintainer.openapiSignHeader
  override val logger = KtorSimpleLogger("Bot.${config.appId}")

  companion object {
    operator fun invoke(config: TencentBotConfig): TencentBotClient {
      return TencentBotClient(config)
    }
  }

  fun auth() = runSuspend {
    connectionMaintainer.auth()
    connectWs()
  }

  private suspend fun connectWs() {
    callOpenapi<TencentWebsocketEndpointResp>(TencentEndpoint.WebSocket) {
      method = HttpMethod.Get
    }.catch {
        logger.error("get websocket endpoint failed.")
      }
      .collect {
        client.ws({
          method = HttpMethod.Get
          url(it.url)
          headers(openapiSignHeader)
        }) {
          val cxt = this@TencentBotClient.toWebSocketSession(call, this)
          while (true) {
            cxt.handleTencentOperation()
          }
        }
      }
  }

  private suspend inline fun <reified T> callOpenapi(
    endpoint: TencentEndpoint,
    noinline block: HttpRequestBuilder.() -> Unit
  ) =
    flow<T> {
      val resp = client.request {
        block.apply {
          headers(openapiSignHeader)
          url("https://sandbox.api.sgroup.qq.com${endpoint.path}")
        }
      }
      if (resp.status == HttpStatusCode.OK) {
        emit(json.decodeFromString(resp.bodyAsText()))
      } else {
        throw Exception()
      }
    }

  private suspend fun ReceiveChannel<Frame>.receiveText() = (receive() as? Frame.Text)?.readText()

  suspend fun <T> ReceiveChannel<Frame>.receiveTencentWebsocketPayload(
    decoder: KSerializer<T>
  ): TencentWebsocketPayload<T>? {
    val textFrame = receiveText() ?: return null
    return json.decodeFromString(TencentWebsocketPayload.serializer(decoder), textFrame)
  }

  override fun close() {
    connectionMaintainer.close()
    client.close()
  }
}
