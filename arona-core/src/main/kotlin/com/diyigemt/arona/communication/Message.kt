package com.diyigemt.arona.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal class TencentMessageIntentsBuilder {
  private val offsets = mutableListOf<TencentMessageIntentSuperType>()
  fun append(intent: TencentMessageIntentSuperType) = this.apply {
    offsets.add(intent)
  }
  fun append(intents: Array<TencentMessageIntentSuperType>) = this.apply {
    offsets.addAll(intents)
  }
  fun build() = offsets
    .map { it.offset }
    .toMutableList()
    .apply { this[0] = 1 shl this[0] }
    .reduce { acc, i -> acc or ( 1 shl i ) }
  fun buildAll() = this.apply {
    append(TencentMessageIntentSuperType.entries.toTypedArray())
  }.build()
}

internal enum class TencentMessageIntentSuperType(val offset: Int) {
  GUILDS(0),
  GUILD_MEMBERS(1),
  GUILD_MESSAGES(9),
  GUILD_MESSAGE_REACTIONS(10),
  DIRECT_MESSAGE(12),
  OPEN_FORUMS_EVENT(18),
  AUDIO_OR_LIVE_CHANNEL_MEMBER(19),
  CLIENT_MESSAGE(25), // 私聊 群聊事件
  INTERACTION(26),
  MESSAGE_AUDIT(27),
  FORUMS_EVENT(28),
  AUDIO_ACTION(29),
  PUBLIC_GUILD_MESSAGES(30)
}

@Serializable
internal data class TencentGuildUser(
  val id: String,
  val bot: Boolean,
  val avatar: String,
  val username: String,
  @SerialName("union_openid")
  val unionOpenid: String = "",
  @SerialName("union_user_account")
  val unionUserAccount: String = ""
)

@Serializable
internal data class TencentGuildMember(
  val nick: String,
  val roles: List<String>,
  @SerialName("joined_at")
  val joinedAt: String,
  val user: TencentGuildUser?
)

@Serializable
internal data class TencentGuildMessage(
  val id: String,
  @SerialName("guild_id")
  val guildId: Long,
  @SerialName("channel_id")
  val channelId: Long,
  val content: String,
  @SerialName("seq")
  val sequence: Long,
  @SerialName("seq_in_channel")
  val sequenceInChannel: Long,
  val timestamp: Long,
  val author: TencentGuildUser,
  val member: TencentGuildMember
)
