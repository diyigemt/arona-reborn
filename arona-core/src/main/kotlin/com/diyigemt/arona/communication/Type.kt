package com.diyigemt.arona.communication

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class TencentBotConfig(
  val appId: String,
  val token: String,
  val secret: String
) {
  fun toAuthConfig() = TencentBotAuthEndpointReq(appId, secret)
}

@Serializable
data class TencentBotAuthEndpointReq(
  val appId: String,
  val clientSecret: String
)

@Serializable
internal data class TencentBotAuthEndpointResp(
  @SerialName("access_token")
  val accessToken: String,
  @SerialName("expires_in")
  val expiresIn: Int
)

@Serializable
internal data class TencentWebsocketEndpointResp(
  val url: String
)

@Serializable
internal data class TencentWebsocketHelloResp(
  @SerialName("heartbeat_interval")
  val heartbeatInterval: Long
)

@Serializable
internal data class TencentWebsocketPayload<T>(
  @SerialName("op")
  val operation: TencentWebsocketOperationType,
  @SerialName("s")
  val serialNumber: Long = 0L,
  @SerialName("t")
  val type: TencentWebsocketEventType,
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

internal object TencentWebsocketEventTypeAsStringSerializer : KSerializer<TencentWebsocketEventType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentWebsocketEventType", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: TencentWebsocketEventType) = encoder.encodeString(value.type)
  override fun deserialize(decoder: Decoder) = TencentWebsocketEventType.fromValue(decoder.decodeString())
}

@Serializable(with = TencentWebsocketEventTypeAsStringSerializer::class)
internal enum class TencentWebsocketEventType(val type: String, val decoder: KSerializer<*>) {
  NULL("NULL", Int.serializer()),
  HELLO("HELLO", TencentWebsocketHelloResp.serializer()),
  C2C_MESSAGE_CREATE("C2C_MESSAGE_CREATE", PrivateMessage.serializer());
  companion object {
    private val TypeMap = entries.associateBy { it.type }
    fun fromValue(type: String) = TypeMap[type] ?: NULL
  }
}

enum class TencentEndpoint(val path: String) {
  WebSocket("/gateway")
}
