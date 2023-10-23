package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.TencentWebsocketOperationManager.handleTencentOperation
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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

internal interface TencentBot : CoroutineScope {
  val client: HttpClient
  val json: Json
  val connectionMaintainer: TencentBotClientConnectionMaintainer
  val openapiSignHeader: HeadersBuilder.() -> Unit
  val logger: Logger
  suspend fun <T> callOpenapi(
    endpoint: TencentEndpoint,
    decoder: KSerializer<T>,
    urlPlaceHolder: Map<String, String> = mapOf(),
    block: HttpRequestBuilder.() -> Unit
  ): Result<T>
}

internal open class TencentBotClient private constructor(val config: TencentBotConfig) : Closeable, TencentBot {
  override val client = HttpClient(CIO) {
    install(WebSockets)
    install(ContentNegotiation) {
      json
    }
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
    callOpenapi(TencentEndpoint.WebSocket, TencentWebsocketEndpointResp.serializer()) {
      method = HttpMethod.Get
    }.onSuccess {
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

  override val coroutineContext: CoroutineContext = EmptyCoroutineContext

  override fun close() {
    client.close()
  }
}
