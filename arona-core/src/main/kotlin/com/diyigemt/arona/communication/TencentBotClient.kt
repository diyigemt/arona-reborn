package com.diyigemt.arona.communication

import com.diyigemt.arona.utils.apiLogger
import com.diyigemt.arona.utils.runSuspend
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.concurrent.timerTask

internal class TencentBotClientConnectionMaintainer(private val bot: TencentBotClient) {
  private val client = bot.client
  private val timer = Timer("", true)
  private var accessToken: String = ""
  val openapiSignHeader: HeadersBuilder.() -> Unit = {
    append("Authorization", "QQBot $accessToken")
    append("X-Union-Appid", bot.config.appId)
  }
  private var accessTokenHeartbeatTask: TimerTask = timerTask { runSuspend { auth() } }
  private var websocketHeartbeatTask: TimerTask = timerTask { websocketHeartbeat() }
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
  private fun websocketHeartbeat() {

  }
  private fun startAccessTokenHeartbeat() =
    timer.scheduleAtFixedRate(accessTokenHeartbeatTask, (7200L - 30L) * 1000, (7200L - 30L) * 1000)

}

class TencentBotClient private constructor(val config: TencentBotConfig) : Closeable {
  val client = HttpClient(CIO) {
    install(WebSockets)
  }
  val json = Json {
    ignoreUnknownKeys = true
  }
  private val openapiSignHeader
    get() = connectionMaintainer.openapiSignHeader
  private val connectionMaintainer = TencentBotClientConnectionMaintainer(this)
  private var serialNumber: Long = 0L


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
    }.collect {
      client.ws({
        method = HttpMethod.Get
        url(it.url)
        headers(openapiSignHeader)
      }) {
        val message = incoming.receive() as? Frame.Text ?: return@ws
        apiLogger.info(message.readText())
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

  override fun close() {
    client.close()
  }
}
