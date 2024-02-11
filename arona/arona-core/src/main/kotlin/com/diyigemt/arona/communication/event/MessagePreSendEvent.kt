package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.message.Message

sealed class MessagePreSendEvent : TencentBotEvent {
  abstract val target: Contact
  override val bot: TencentBot
    get() = target.bot

  abstract var message: Message
}

data class GroupMessagePreSendEvent(
  override val target: Group,
  override var message: Message
) : MessagePreSendEvent()

data class FriendMessagePreSendEvent(
  override val target: FriendUser,
  override var message: Message
) : MessagePreSendEvent()

data class GuildMessagePreSendEvent(
  override val target: Guild,
  override var message: Message
) : MessagePreSendEvent()

data class ChannelMessagePreSendEvent(
  override val target: Channel,
  override var message: Message
) : MessagePreSendEvent()