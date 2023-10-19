package com.diyigemt.arona.communication

import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.userLogger
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
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
internal data class TencentWebsocketHelloResp(
  @SerialName("heartbeat_interval")
  val heartbeatInterval: Long
)

@Serializable
internal data class TencentWebsocketSessionReq(
  val token: String,
  val intents: Int,
  val shard: List<Int>,
  val properties: Map<String, String> = emptyMap()
)

@Serializable
internal data class TencentWebsocketSessionUserResp(
  val id: String,
  val username: String,
  val bot: Boolean
)

@Serializable
internal data class TencentWebsocketSessionResp(
  val version: Int,
  @SerialName("session_id")
  val sessionId: String,
  val user: TencentWebsocketSessionUserResp,
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
  abstract suspend fun TencentBotClientWebSocketSession.handler(payload: TencentWebsocketPayload<T>)
}

@Suppress("UNUSED")
internal object TencentWebsocketHelloHandler : TencentWebsocketOperationHandler<TencentWebsocketHelloResp>() {
  override val type = TencentWebsocketOperationType.Hello
  override val decoder = TencentWebsocketHelloResp.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(payload: TencentWebsocketPayload<TencentWebsocketHelloResp>) {
    // 开始心跳
    connectionMaintainer.startWebsocketHeartbeat(payload.data.heartbeatInterval) {
      outgoing.send(
        Frame.Text(
          json.encodeToString(
            TencentWebsocketPayload(
              operation = TencentWebsocketOperationType.Heartbeat,
              serialNumber = 0,
              type = TencentWebsocketEventType.NULL,
              data = if (serialNumber == 0L) null else serialNumber
            )
          )
        )
      )
    }
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketHeartbeatAckHandler : TencentWebsocketOperationHandler<Unit>() {
  override val type = TencentWebsocketOperationType.HeartbeatAck
  override val decoder = Unit.serializer()
  override suspend fun TencentBotClientWebSocketSession.handler(payload: TencentWebsocketPayload<Unit>) {
    userLogger.info("heartbeat")
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
    userLogger.debug("recev websocket data: {}, type: {}", plainText, preData.operation)
    val data = json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), plainText)
    serialNumber = if (data.serialNumber == 0L) serialNumber else data.serialNumber
    handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, data)
  }

  private suspend fun ReceiveChannel<Frame>.receiveText() = (receive() as? Frame.Text)?.readText()
}

internal fun TencentBotClient.toWebSocketSession(call: HttpClientCall, ctx: DefaultWebSocketSession) =
  TencentBotClientWebSocketSession(call, ctx, this)

internal class TencentBotClientWebSocketSession(
  override val call: HttpClientCall,
  delegate: DefaultWebSocketSession,
  bot: TencentBotClient
) : ClientWebSocketSession, DefaultWebSocketSession by delegate, TencentBot by bot
