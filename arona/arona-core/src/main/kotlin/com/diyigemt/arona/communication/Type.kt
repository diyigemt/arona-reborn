package com.diyigemt.arona.communication

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 顶层 [com.diyigemt.arona.communication.contact.ContactList] 的有界缓存阈值.
 *
 * `maximumSize` 触发 Caffeine size-based eviction (window TinyLFU, 非严格 LRU);
 * `expireAfterAccessSeconds` 触发空闲过期 (entry 在该秒数内未被访问即可被清).
 * 默认值在 [TencentBotContactCacheConfig] 给, 按 6w DAU 量级估算: 见各 ContactList 字段说明.
 */
@Serializable
data class ContactCacheTuning(
  val maximumSize: Long,
  val expireAfterAccessSeconds: Long,
)

/**
 * 三个顶层 ContactList (`bot.guilds` / `bot.groups` / `bot.friends`) 的容量配置.
 * inner ContactList (Guild/Group/Channel 内嵌的 members/channels) 当前仍走无界 ConcurrentHashMap,
 * 跟随 outer 容器一并被 GC.
 */
@Serializable
data class TencentBotContactCacheConfig(
  // 频道一般每 bot 几十~几百个; 给 1k + 7d 提供大量余量.
  val guilds: ContactCacheTuning = ContactCacheTuning(
    maximumSize = 1_000,
    expireAfterAccessSeconds = 7 * 24 * 60 * 60L,
  ),
  // 群上限以"机器人加入的群"为基数; 6w DAU 业务多在数千群级别.
  val groups: ContactCacheTuning = ContactCacheTuning(
    maximumSize = 10_000,
    expireAfterAccessSeconds = 24 * 60 * 60L,
  ),
  // 好友/C2C 用户基数最大, 也是 cache miss 频率最高的; 上限设到 DAU 量级以上.
  val friends: ContactCacheTuning = ContactCacheTuning(
    maximumSize = 100_000,
    expireAfterAccessSeconds = 24 * 60 * 60L,
  ),
)

@Serializable
data class TencentBotConfig(
  val id: String,
  val appId: String,
  val token: String,
  val secret: String,
  val public: Boolean = false,
  val debug: Boolean = false,
  val shard: List<Int> = listOf(0, 1), // [0,4] 分4片当前第0片, 默认[0,1]
  val contactCache: TencentBotContactCacheConfig = TencentBotContactCacheConfig(),
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

// Sprint 2.1 Part B: WS gateway endpoint DTO (TencentWebsocketEndpointResp / ShardEndpointResp /
// ShardSessionStartLimit) 随 WS 死代码一并删除, 全仓库无引用.

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
  // Sprint 2.1 Part B: WebSocket / ShardWebSocket gateway endpoints 全仓库无引用, 删除;
  // Interactions 仍被 callback ack 路径 (TencentWebsocketInteractionNotifyReq) 使用, 保留.
  Interactions("/interactions/{interaction_id}"), // 通知后台接收到推送消息
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
