package com.diyigemt.arona.communication

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class TencentBotConfig(
  val id: String,
  val appId: String,
  val token: String,
  val secret: String,
  val public: Boolean = false,
  val debug: Boolean = false,
  val shard: List<Int> = listOf(0, 1) // [0,4] 分4片当前第0片, 默认[0,1]
) {
  fun toAuthConfig() = TencentBotAuthEndpointReq(appId, secret)
}

@Serializable
data class TencentBotAuthEndpointReq(
  val appId: String,
  val clientSecret: String,
)

@Serializable
internal data class TencentBotAuthEndpointResp(
  @SerialName("access_token")
  val accessToken: String,
  @SerialName("expires_in")
  val expiresIn: Int,
)

@Serializable
internal data class TencentWebsocketEndpointResp(
  val url: String,
)

@Serializable
internal data class TencentWebsocketShardEndpointResp(
  val url: String,
  val shards: Int,
  @SerialName("session_start_limit")
  val sessionStartLimit: TencentWebsocketShardSessionStartLimit
)

@Serializable
internal data class TencentWebsocketShardSessionStartLimit(
  val total: Int, // 每 24 小时可创建 Session 数
  val remaining: Int, // 目前还可以创建的 Session 数
  @SerialName("reset_after")
  val resetAfter: Int, // 重置计数的剩余时间(ms)
  @SerialName("max_concurrency")
  val maxConcurrency: Int // 每 5s 可以创建的 Session 数
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
  RESUMED("RESUMED"), // websocket 连接恢复成功
  MESSAGE_CREATE("MESSAGE_CREATE"), // 频道全量消息发送
  AT_MESSAGE_CREATE("AT_MESSAGE_CREATE"), // 频道@机器人消息
  DIRECT_MESSAGE_CREATE("DIRECT_MESSAGE_CREATE"), // 频道私聊消息
  C2C_MESSAGE_CREATE("C2C_MESSAGE_CREATE"), // 私聊消息发送
  GROUP_AT_MESSAGE_CREATE("GROUP_AT_MESSAGE_CREATE"),// 群聊@机器人消息
  GUILD_CREATE("GUILD_CREATE"), // 机器人被加入频道
  GUILD_DELETE("GUILD_DELETE"), // 机器人被踢出频道/频道解散
  GROUP_ADD_ROBOT("GROUP_ADD_ROBOT"), // 机器人被加入群
  GROUP_DEL_ROBOT("GROUP_DEL_ROBOT"), // 机器人被踢出群
  GROUP_MSG_REJECT("GROUP_MSG_REJECT"), // 群拒绝机器人的主动消息
  GROUP_MSG_RECEIVE("GROUP_MSG_RECEIVE"), // 群允许机器人的主动消息
  FRIEND_ADD("FRIEND_ADD"), // 机器人被加入好友
  FRIEND_DEL("FRIEND_DEL"), // 机器人被删除好友
  C2C_MSG_REJECT("C2C_MSG_REJECT"), // 好友拒绝机器人的主动消息
  C2C_MSG_RECEIVE("C2C_MSG_RECEIVE"), // 好友允许机器人的主动消息
  INTERACTION_CREATE("INTERACTION_CREATE"), // 回调按钮被点击
  A("GUILD_CREATE");

  companion object {
    private val TypeMap = entries.associateBy { it.type }
    fun fromValue(type: String) = TypeMap[type] ?: NULL
  }
}

enum class TencentEndpoint(val path: String) {
  WebSocket("/gateway"),
  ShardWebSocket("/gateway/bot"),
  Interactions("/interactions/{interaction_id}"), // 通知后台接收到websocket推送的消息
  PostFriendMessage("/v2/users/{openid}/messages"), // 向用户发送私信消息
  PostFriendRichMessage("/v2/users/{openid}/files"), // 向用户发送私信消息
  PostGroupMessage("/v2/groups/{group_openid}/messages"), // 向群聊发送消息
  PostGroupRichMessage("/v2/groups/{group_openid}/files"), // 向群聊发送消息
  PostGuildMessage("/channels/{channel_id}/messages"), // 向子频道发送消息
  PostGuildMemberMessage("/dms/{guild_id}/messages"), // 向频道成员发送私聊消息
  GetBotGuildList("/users/@me/guilds"), // 获取机器人/创建者加入的频道列表
  GetGuildMemberList("/guilds/{guild_id}/members"), // 获取频道的成员列表
  GetGuildChannelList("/guilds/{guild_id}/channels"), // 获取频道的子频道列表
  DeleteFriendMessage("/v2/users/{openid}/messages/{message_id}"), // 撤回好友消息
  DeleteGroupMessage("/v2/groups/{group_openid}/messages/{message_id}"), // 撤回群消息
  DeleteGuildMessage("/channels/{channel_id}/messages/{message_id}"), // 撤回频道消息
  DeleteGuildMemberMessage("/dms/{guild_id}/messages/{message_id}"); // 撤回频道私聊消息
  companion object {
    fun TencentEndpoint.isGuildEndMessageEndpoint() = this == PostGuildMessage
    fun TencentEndpoint.isUserOrGroupMessageEndpoint() = this == PostGroupMessage || this == PostFriendMessage
  }
}
