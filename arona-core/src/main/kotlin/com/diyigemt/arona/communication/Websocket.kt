package com.diyigemt.arona.communication

import com.diyigemt.arona.communication.TencentWebsocketDispatchEventManager.handleTencentDispatchEvent
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.cancel
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

@Serializable
internal data class TencentWebsocketHelloResp(
  @SerialName("heartbeat_interval")
  val heartbeatInterval: Long
)

@Serializable
internal data class TencentWebsocketIdentifyReq(
  val token: String,
  val intents: Int,
  val shard: List<Int>,
  val properties: Map<String, String> = emptyMap()
)

@Serializable
internal data class TencentWebsocketIdentifyUserResp(
  val id: String,
  val username: String,
  val bot: Boolean
)

@Serializable
internal data class TencentWebsocketIdentifyResp(
  val version: Int,
  @SerialName("session_id")
  val sessionId: String,
  val user: TencentWebsocketIdentifyUserResp,
  val shard: List<Int>
)

@Serializable
internal data class TencentWebsocketPayload<T>(
  @SerialName("op")
  val operation: TencentWebsocketOperationType = TencentWebsocketOperationType.Null,
  @SerialName("s")
  val serialNumber: Long = 0L,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL,
  @SerialName("d")
  val data: T
)

@Serializable
internal data class TencentWebsocketPayload0(
  @SerialName("op")
  val operation: TencentWebsocketOperationType,
  @SerialName("s")
  val serialNumber: Long = 0L,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL
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
    source: String
  ) {
    payload ?: return
    heartbeatInterval = payload.data.heartbeatInterval
    // 发送鉴权
    sendApiData(
      TencentWebsocketPayload(
        operation = TencentWebsocketOperationType.Identify,
        data = TencentWebsocketIdentifyReq(
          token = connectionMaintainer.botToken,
          intents = TencentMessageIntentsBuilder()
            .append(TencentMessageIntentSuperType.GUILD_MESSAGES)
            .build(),
          shard = listOf(0, 1)
        )
      )
    )
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketServerInvalidSessionHandler :
  TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.InvalidSession
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<Unit>?,
    source: String
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
    source: String
  ) {
    val preData = json.decodeFromString<TencentWebsocketPayload0>(source)
    handleTencentDispatchEvent(preData.type, source)
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketHeartbeatAckHandler : TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.HeartbeatAck
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(
    payload: TencentWebsocketPayload<Unit>?,
    source: String
  ) {
    logger.info("heartbeat")
  }
}

internal object TencentWebsocketOperationManager {
  private val map by lazy {
    ReflectionUtil.scanInterfacePetObjectInstance(TencentWebsocketOperationHandler::class).associateBy { it.type }
  }
  internal suspend fun TencentBotClientWebSocketSession.handleTencentOperation() {
    val plainText = incoming.receiveText() ?: return
    val preData = json.decodeFromString<TencentWebsocketPayload0>(plainText)
    val handler = map[preData.operation] ?: return
    logger.debug("recev websocket data: {}, type: {}", plainText, preData.operation)
    val data = runCatching {
      json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), plainText)
    }.getOrNull()
    serialNumber = if (preData.serialNumber == 0L) serialNumber else preData.serialNumber
    handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, data, plainText)
  }

  private suspend fun ReceiveChannel<Frame>.receiveText() = (receive() as? Frame.Text)?.readText()
}

internal fun TencentBotClient.toWebSocketSession(call: HttpClientCall, ctx: DefaultWebSocketSession) =
  TencentBotClientWebSocketSession(call, ctx, this)

internal class TencentBotClientWebSocketSession(
  override val call: HttpClientCall,
  delegate: DefaultWebSocketSession,
  bot: TencentBotClient
) : ClientWebSocketSession, DefaultWebSocketSession by delegate, TencentBot by bot {
  var serialNumber: Long = 0L // websocket 最后一次通信消息序号
  var heartbeatInterval = 41000L // websocket心跳周期(毫秒)
  lateinit var sessionId: String
  override val coroutineContext = delegate.coroutineContext + bot.coroutineContext
  suspend inline fun <reified T> sendApiData(payload: TencentWebsocketPayload<T>) = send(json.encodeToString(payload))
}
