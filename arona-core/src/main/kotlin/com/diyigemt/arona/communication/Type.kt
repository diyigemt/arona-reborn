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
