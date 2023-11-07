package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.TencentChannelMessageRaw
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

abstract class TencentMessageEvent(
  override val bot: TencentBot,
  val message: MessageChain,
) : TencentEvent() {
  abstract val subject: Contact
  abstract val sender: User
}

// 频道消息事件
class TencentGuildMessageEvent internal constructor(
  message: MessageChain,
  override val sender: GuildChannelMember,
) : TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.channel
}

// 频道私聊消息事件
class TencentGuildPrivateMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: GuildMember,
  internal val sourceMessage: TencentChannelMessageRaw,
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender.guild
}

class TencentSingleMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: SingleUser,
  internal val sourceMessage: TencentChannelMessageRaw,
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender
}

class TencentGroupMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: GroupMember,
  internal val sourceMessage: TencentChannelMessageRaw,
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender.group
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
