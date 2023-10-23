package com.diyigemt.arona.communication

import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

@Suppress("UNUSED")
internal object TencentWebsocketReadyHandler : TencentWebsocketDispatchEventHandler<TencentWebsocketIdentifyResp>() {
  override val type = TencentWebsocketEventType.READY
  override val decoder = TencentWebsocketIdentifyResp.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentWebsocketIdentifyResp) {
    logger.info("websocket receive hello from server")
    sessionId = payload.sessionId
    // 开始心跳
    connectionMaintainer.startWebsocketHeartbeat(heartbeatInterval) {
      sendApiData(
        TencentWebsocketPayload(
          operation = TencentWebsocketOperationType.Heartbeat,
          serialNumber = 0,
          type = TencentWebsocketEventType.NULL,
          data = if (serialNumber == 0L) null else serialNumber
        )
      )
    }
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketMessageCreateHandler : TencentWebsocketDispatchEventHandler<TencentGuildMessage>() {
  override val type = TencentWebsocketEventType.MESSAGE_CREATE
  override val decoder = TencentGuildMessage.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentGuildMessage) {
    callOpenapi(TencentEndpoint.PostGuildMessage, Unit.serializer(), mapOf("channel_id" to payload.channelId)) {
      method = HttpMethod.Post
      setBody(json.encodeToString(TencentMessageBuilder().append("测试回复").build().apply {
        messageId = payload.id
        image = "https://arona.cdn.diyigemt.com/image/some/%E9%95%BF%E8%8D%89.png"
      }))
    }.onSuccess {
      logger.info("post message success")
    }.onFailure {
      logger.error(it)
    }
  }
}

internal abstract class TencentWebsocketDispatchEventHandler<T> {
  abstract val type: TencentWebsocketEventType
  abstract val decoder: KSerializer<T>
  abstract suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: T)
}

@Suppress("UNUSED")
internal object TencentWebsocketDispatchEventManager {
  private val map by lazy {
    ReflectionUtil.scanInterfacePetObjectInstance(TencentWebsocketDispatchEventHandler::class).associateBy { it.type }
  }

  internal suspend fun TencentBotClientWebSocketSession.handleTencentDispatchEvent(
    event: TencentWebsocketEventType,
    source: String
  ) {
    val handler = map[event] ?: return
    logger.debug("recev dispatch event: {}, data: {}", event, source)
    runCatching {
      json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), source)
    }.onSuccess {
      handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, it.data)
    }.onFailure {
      logger.error(it)
      logger.error("decode dispatch event failed event: {}, data: {}", event, source)
    }
  }
}

interface TencentEvent
