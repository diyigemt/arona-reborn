package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentBotClient
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.event.TencentBotWebsocketConnectionLostEvent
import com.diyigemt.arona.communication.event.TencentBotWebsocketHandshakeSuccessEvent
import com.diyigemt.arona.communication.event.TencentWebsocketDispatchEventManager.handleTencentDispatchEvent
import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.util.logging.*
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

@Serializable
internal data class TencentWebsocketInteractionNotifyReq(
  /**
   * 0: 成功
   *
   * 1: 操作失败
   *
   * 2: 操作频繁
   *
   * 3: 重复操作
   *
   * 4: 没有权限
   *
   * 5: 仅管理员操作
   */
  val code: Int,
)

@Serializable
internal data class TencentWebsocketHelloResp(
  @SerialName("heartbeat_interval")
  val heartbeatInterval: Long,
)

@Serializable
internal data class TencentWebsocketIdentifyReq(
  val token: String,
  val intents: Int,
  val shard: List<Int>,
  val properties: Map<String, String> = emptyMap(),
)

@Serializable
internal data class TencentWebsocketResumeReq(
  val token: String,
  @SerialName("session_id")
  val sessionId: String,
  @SerialName("seq")
  val serialNumber: Long
)

@Serializable
internal data class TencentWebsocketIdentifyUserResp(
  val id: String,
  val username: String,
  val bot: Boolean,
)

@Serializable
internal data class TencentWebsocketIdentifyResp(
  val version: Int,
  @SerialName("session_id")
  val sessionId: String,
  val user: TencentWebsocketIdentifyUserResp,
  val shard: List<Int>,
)

@Serializable
internal data class TencentWebsocketPayload<T>(
  val id: String? = null, // interactions下发伴随的id, 如果有, 则需要调用openapi的接口通知后台消息收到
  @SerialName("op")
  val operation: TencentWebsocketOperationType = TencentWebsocketOperationType.Null,
  @SerialName("s")
  val serialNumber: Long = 0L,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL,
  @SerialName("d")
  val data: T,
)

@Serializable
internal open class TencentWebsocketPayload0(
  val id: String? = null, // interactions下发伴随的id, 如果有, 则需要调用openapi的接口通知后台消息收到
  @SerialName("op")
  val operation: TencentWebsocketOperationType = TencentWebsocketOperationType.Null,
  @SerialName("s")
  val serialNumber: Long = 0L,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL,
)

internal object TencentWebsocketOperationTypeAsIntSerializer : KSerializer<TencentWebsocketOperationType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentWebsocketEventType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentWebsocketOperationType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentWebsocketOperationType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentWebsocketOperationTypeAsIntSerializer::class)
internal enum class TencentWebsocketOperationType(val code: Int) {
  Null(-1),
  Dispatch(0),
  Heartbeat(1),
  Identify(2),
  Resume(6),
  Reconnect(7),
  InvalidSession(9),
  Hello(10),
  HeartbeatAck(11),
  HttpCallbackAck(12);

  companion object {
    private val TypeMap = entries.associateBy { it.code }
    fun fromValue(code: Int) = TypeMap[code] ?: Null
  }
}

internal abstract class TencentWebsocketOperationHandler<T> {
  abstract val type: TencentWebsocketOperationType
  abstract val decoder: KSerializer<T>
  abstract suspend fun TencentBotClientWebSocketSession.handler(payload: TencentWebsocketPayload<T>?, source: String)
}

@Suppress("UNUSED")
internal object TencentWebsocketHelloHandler : TencentWebsocketOperationHandler<TencentWebsocketHelloResp>() {
  override val type = TencentWebsocketOperationType.Hello
  override val decoder = TencentWebsocketHelloResp.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<TencentWebsocketHelloResp>?,
    source: String,
  ) {
    payload ?: return
    heartbeatInterval = payload.data.heartbeatInterval
    TencentBotWebsocketHandshakeSuccessEvent(bot).broadcast()
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketServerInvalidSessionHandler :
  TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.InvalidSession
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<Unit>?,
    source: String,
  ) {
    logger.warn("invalid session: $source")
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketDispatchHandler : TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.Dispatch
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<Unit>?,
    source: String,
  ) {
    val preData = json.decodeFromString<TencentWebsocketPayload0>(source)
    // TODO 为啥不让我通知后台
    // 通知后台interaction下发成功
//    if (preData.id != null) {
//      withContext(coroutineContext) {
//        bot.callOpenapi(
//          TencentEndpoint.Interactions,
//          urlPlaceHolder = mapOf("interaction_id" to preData.id)
//        ) {
//          method = HttpMethod.Put
//          setBody(bot.json.encodeToString(TencentWebsocketInteractionNotifyReq(0)))
//        }
//      }
//    }
    handleTencentDispatchEvent(preData.type, source)
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketHeartbeatAckHandler : TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.HeartbeatAck
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<Unit>?,
    source: String,
  ) {
    logger.info("heartbeat")
  }
}

internal object TencentWebsocketOperationManager {
  private val map by lazy {
    ReflectionUtil.scanInterfacePetObjectInstance(TencentWebsocketOperationHandler::class).associateBy { it.type }
  }

  @OptIn(DelicateCoroutinesApi::class)
  internal suspend fun TencentBotClientWebSocketSession.handleTencentOperation() : Boolean {
    if (incoming.isClosedForReceive) {
      TencentBotWebsocketConnectionLostEvent(bot).broadcast()
      return false
    }
    return runCatching {
      for (message in incoming) {
        when (message) {
          is Frame.Close -> TencentBotWebsocketConnectionLostEvent(bot).broadcast()
          is Frame.Text -> {
            val plainText = message.readText()
            val preData = json.decodeFromString<TencentWebsocketPayload0>(plainText)
            val handler = map[preData.operation] ?: continue
            logger.debug("recev websocket data: {}, type: {}", plainText, preData.operation)
            val data = runCatching {
              json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), plainText)
            }.getOrNull()
            serialNumber = if (preData.serialNumber == 0L) serialNumber else preData.serialNumber
            handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, data, plainText)
          }
          else -> Unit
        }
      }
      true
    }.onFailure {
      if (it is ClosedReceiveChannelException) {
        TencentBotWebsocketConnectionLostEvent(bot).broadcast()
        return false
      } else {
        logger.error(it)
      }
    }.getOrDefault(true)
  }

  private suspend fun ReceiveChannel<Frame>.receiveText() = (receive() as? Frame.Text)?.readText()
}

internal fun TencentBotClient.toWebSocketSession(call: HttpClientCall, ctx: DefaultWebSocketSession) =
  TencentBotClientWebSocketSession(call, this, ctx)

internal class TencentBotClientWebSocketSession(
  override val call: HttpClientCall,
  val bot: TencentBotClient,
  delegate: DefaultWebSocketSession,
) : ClientWebSocketSession, DefaultWebSocketSession by delegate {
  var serialNumber: Long = 0L // websocket 最后一次通信消息序号
  var heartbeatInterval = 41000L // websocket心跳周期(毫秒)
  lateinit var sessionId: String
  override val coroutineContext = delegate.coroutineContext + bot.coroutineContext
  val logger = bot.logger
  val json = bot.json
  suspend inline fun <reified T> sendApiData(payload: TencentWebsocketPayload<T>) = send(json.encodeToString(payload))
}
