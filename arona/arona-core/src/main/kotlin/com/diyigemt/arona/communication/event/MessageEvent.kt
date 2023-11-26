package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.message.MessageChain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

abstract class TencentMessageEvent(
  override val bot: TencentBot,
  val message: MessageChain,
) : TencentEvent(), TencentBotEvent {
  abstract val subject: Contact
  abstract val sender: User
}

interface TencentGuildEvent : TencentBotEvent {
  val guild: Guild
}
interface TencentGroupEvent : TencentBotEvent {
  val group: Group
}
interface TencentFriendEvent : TencentBotEvent {
  val friend: FriendUser
}

// 频道消息事件
class TencentGuildMessageEvent internal constructor(
  message: MessageChain,
  override val sender: GuildChannelMember,
) : TencentGuildEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.channel
  override val guild get() = sender.guild
  override fun toString(): String {
    return "[Guild(${subject.id})] ${sender.id} -> $message"
  }
}

// 频道私聊消息事件
class TencentGuildPrivateMessageEvent internal constructor(
  message: MessageChain,
  override val sender: GuildMember,
) : TencentGuildEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.channel
  override val guild get() = sender.guild
  override fun toString(): String {
    return "[PrivateChannel(${subject.id})] ${sender.id} -> $message"
  }
}

class TencentFriendMessageEvent internal constructor(
  message: MessageChain,
  override val sender: FriendUser,
) : TencentFriendEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender
  override val friend get() = sender
}

class TencentGroupMessageEvent internal constructor(
  message: MessageChain,
  override val sender: GroupMember,
) : TencentGroupEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.group
  override val group get() = sender.group
  override fun toString(): String {
    return "[Group(${subject.id})] ${sender.id} -> $message"
  }
}

interface TencentBotUserChangeEvent : TencentBotEvent {
  val subject: Contact
}

class TencentFriendAddEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentFriendEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val friend: FriendUser
    get() = TODO("Not yet implemented")
  override val subject get() = friend
}

class TencentFriendDeleteEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentFriendEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val friend: FriendUser
    get() = TODO("Not yet implemented")
  override val subject get() = friend
}

class TencentGroupAddEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentGroupEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val group: Group
    get() = TODO("Not yet implemented")
  override val subject get() = group
}

class TencentGroupDeleteEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentGroupEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val group: Group
    get() = TODO("Not yet implemented")
  override val subject get() = group
}

class TencentGuildAddEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentGuildEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val guild: Guild
    get() = TODO("Not yet implemented")
  override val subject get() = guild
}

class TencentGuildDeleteEvent internal constructor(
  val id: String,
  override val bot: TencentBot
) : TencentGuildEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val guild: Guild
    get() = TODO("Not yet implemented")
  override val subject get() = guild
}

suspend inline fun <reified P : TencentMessageEvent> P.nextMessage(
  timeoutMillis: Long = -1,
  noinline filter: suspend P.(P) -> Boolean = { true },
): MessageChain = nextMessage(timeoutMillis, false, filter)

suspend inline fun <reified P : TencentMessageEvent> P.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend P.(P) -> Boolean = { true },
): MessageChain {
  val mapper: suspend (P) -> P? = createMapper(filter)

  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent(mapper)
    }
  }).message
}

suspend inline fun <reified P : TencentMessageEvent> P.nextMessageOrNull(
  timeoutMillis: Long,
  noinline filter: suspend P.(P) -> Boolean = { true },
): MessageChain? {
  require(timeoutMillis > 0) { "timeoutMillis must be > 0" }

  val mapper: suspend (P) -> P? = createMapper(filter)

  return withTimeoutOrNull(timeoutMillis) {
    GlobalEventChannel.syncFromEvent(mapper)
  }?.message
}

@PublishedApi
internal inline fun <reified P : TencentMessageEvent> P.createMapper(crossinline filter: suspend P.(P) -> Boolean): suspend (P) -> P? =
  mapper@{ event ->
    if (!event.isContextIdenticalWith(this)) return@mapper null
    if (!filter(event, event)) return@mapper null
    event
  }


fun TencentMessageEvent.isContextIdenticalWith(another: TencentMessageEvent): Boolean {
  return this.sender == another.sender && this.subject == another.subject
}
