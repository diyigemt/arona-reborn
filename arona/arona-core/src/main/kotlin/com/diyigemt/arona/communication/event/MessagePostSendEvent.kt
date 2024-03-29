package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentApiErrorException
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageReceipt

sealed class MessagePostSendEvent<C: Contact> : TencentBotEvent, AbstractEvent() {
  abstract val target: C
  final override val bot get() = target.bot
  abstract val message: MessageChain
  abstract val exception: Throwable?
  abstract val receipt: MessageReceipt<C>?
}

@get:JvmSynthetic
inline val MessagePostSendEvent<*>.isSuccess: Boolean
  get() = exception == null

@get:JvmSynthetic
inline val MessagePostSendEvent<*>.isFailure: Boolean
  get() = exception != null

@get:JvmSynthetic
inline val MessagePostSendEvent<*>.isTencentError: Boolean
  get() = exception is TencentApiErrorException

data class GroupMessagePostSendEvent(
  override val target: Group,
  override val message: MessageChain,
  override val exception: Throwable?,
  override val receipt: MessageReceipt<Group>?
) : MessagePostSendEvent<Group>() {
  override fun toString(): String {
    return "Group(${target.id}) <- $message"
  }
}

data class FriendMessagePostSendEvent(
  override val target: FriendUser,
  override val message: MessageChain,
  override val exception: Throwable?,
  override val receipt: MessageReceipt<FriendUser>?
) : MessagePostSendEvent<FriendUser>() {
  override fun toString(): String {
    return "Friend(${target.id}) <- $message"
  }
}

data class GuildMessagePostSendEvent(
  override val target: GuildMember,
  override val message: MessageChain,
  override val exception: Throwable?,
  override val receipt: MessageReceipt<GuildMember>?
) : MessagePostSendEvent<GuildMember>() {
  override fun toString(): String {
    return "Guild(${target.guild.id}) <- $message"
  }
}

data class ChannelMessagePostSendEvent(
  override val target: Channel,
  override val message: MessageChain,
  override val exception: Throwable?,
  override val receipt: MessageReceipt<Channel>?
) : MessagePostSendEvent<Channel>() {
  override fun toString(): String {
    return "Channel(${target.id}) <- $message"
  }
}
