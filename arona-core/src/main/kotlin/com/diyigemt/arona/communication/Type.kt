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
internal enum class TencentWebsocketEventType(val type: String) {
  NULL("NULL"),
  READY("READY"), // websocket鉴权成功
  MESSAGE_CREATE("MESSAGE_CREATE"), // 频道全量消息发送
  AT_MESSAGE_CREATE("AT_MESSAGE_CREATE"), // 频道@机器人消息
  DIRECT_MESSAGE_CREATE("DIRECT_MESSAGE_CREATE"), // 频道私聊消息
  C2C_MESSAGE_CREATE("C2C_MESSAGE_CREATE"), // 私聊消息发送
  GROUP_AT_MESSAGE_CREATE("GROUP_AT_MESSAGE_CREATE"); // 群聊@机器人消息
  companion object {
    private val TypeMap = entries.associateBy { it.type }
    fun fromValue(type: String) = TypeMap[type] ?: NULL
  }
}

enum class TencentEndpoint(val path: String) {
  WebSocket("/gateway"),
  Interactions("/interactions/{interaction_id}"), // 通知后台接收到websocket推送的消息
  PostSingleUserMessage("/v2/users/{openid}/messages"), // 向用户发送私信消息
  PostGroupMessage("/v2/groups/{group_openid}/messages"), // 向群聊发送消息
  PostGuildMessage("/channels/{channel_id}/messages"), // 向子频道发送消息
  GetBotGuildList("/users/@me/guilds"), // 获取机器人/创建者加入的频道列表
  GetGuildMemberList("/guilds/{guild_id}/members"), // 获取频道的成员列表
  GetGuildChannelList("/guilds/{guild_id}/channels"), // 获取频道的子频道列表
}
