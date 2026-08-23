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
  override val eventId: String,
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
  override val eventId: String,
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
  override val eventId: String,
  override val sender: FriendUser,
) : TencentFriendEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender
  override val friend get() = sender
  override fun toString(): String {
    return "[Friend(${subject.id})] ${sender.id} -> $message"
  }
}

/**
 * 群消息引用的原文. 平台在引用消息 (`message_type=103`) 里把被引用内容内联下发, 这里只保留文本与其 idx,
 * 不解析 `message_scene.ext`, 也不做出站 idx 配对 —— "引用了什么" 靠原文即可回答.
 */
data class QuotedMessage(
  val content: String,
  /** 被引用消息的平台 idx (`REFIDX_…`), 不透明, 仅作诊断/去重参考. */
  val msgIdx: String?,
)

class TencentGroupMessageEvent internal constructor(
  message: MessageChain,
  override val eventId: String,
  override val sender: GroupMember,
  /**
   * 该消息是否 @ 了机器人自身. 来源于群消息 payload 的 mentions.is_you, 与 content 文本无关.
   */
  val isAtBot: Boolean = false,
  /**
   * 群消息 payload 中 author.username 提供的发送者平台展示名. 旧 payload 不下发故可空, 仅作展示:
   * 不参与成员身份判定, 也不写入 [GroupMember] 缓存 (它是逐条消息的瞬态资料, 同一成员不同消息可能不同).
   */
  val platformUsername: String? = null,
  /**
   * 平台下发的消息创建时间 (ISO8601 字符串, 如 `2023-07-05T15:06:43+08:00`), 原样透传不解析.
   * 仅真实 webhook 路径填充; mock / 旧路径为 null, 消费方按 "未知" 处理.
   */
  val timestamp: String? = null,
  /**
   * 本条消息引用的原文; 非引用消息 / 平台未内联原文 / mock 路径为 null. 引用 bot 自身消息时平台还会自动 @ bot
   * (体现在 [isAtBot]), 两者互不推导.
   */
  val quoted: QuotedMessage? = null,
) : TencentGroupEvent, TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.group
  override val group get() = sender.group
  override fun toString(): String {
    return "[Group(${subject.id})] ${sender.id} -> $message"
  }
}

interface TencentBotUserChangeEvent : TencentBotEvent {
  val subject: Contact
  val user: User
  val eventId: String
}

class TencentFriendAddEvent internal constructor(
  override val friend: FriendUser,
  override val eventId: String,
) : TencentFriendEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val user get() = friend
  override val subject get() = friend
  override val bot get() = user.bot
  override fun toString(): String {
    return "TencentFriendAddEvent(user=${user.id}"
  }
}

class TencentFriendDeleteEvent internal constructor(
  override val friend: FriendUser,
  override val eventId: String,
) : TencentFriendEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val user get() = friend
  override val subject get() = friend
  override val bot get() = user.bot
  override fun toString(): String {
    return "TencentFriendAddEvent(user=${user.id}"
  }
}

class TencentGroupAddEvent internal constructor(
  override val user: GroupMember,
  override val eventId: String,
) : TencentGroupEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val group get() = user.group
  override val subject get() = group
  override val bot get() = user.bot
  override fun toString(): String {
    return "TencentGroupAddEvent(group=${group.id}, user=${user.id})"
  }
}

class TencentGroupDeleteEvent internal constructor(
  override val user: GroupMember,
  override val eventId: String,
) : TencentGroupEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val group get() = user.group
  override val subject get() = group
  override val bot get() = user.bot
  override fun toString(): String {
    return "TencentGroupDeleteEvent(group=${group.id}, user=${user.id})"
  }
}

class TencentGuildAddEvent internal constructor(
  override val user: GuildMember,
  override val eventId: String,
) : TencentGuildEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val guild get() = user.guild
  override val subject get() = guild
  override val bot get() = user.bot
}

class TencentGuildDeleteEvent internal constructor(
  override val user: GuildMember,
  override val eventId: String,
) : TencentGuildEvent, TencentBotUserChangeEvent, TencentEvent() {
  override val guild get() = user.guild
  override val subject get() = guild
  override val bot get() = user.bot
}

/**
 * 主动消息开关事件的统一标记: 用户在群/单聊侧允许 ([accept]=true) 或拒绝机器人的主动消息推送.
 *
 * 刻意不实现 [TencentBotUserChangeEvent] —— 那套接口被 BuiltInCommands 的建档 listener 消费,
 * 开关事件混进去会被误当作联系人新增建档. [timestamp] 是平台事件时间 (好友侧 wire 是字符串,
 * 已在 handler 归一化, 解析失败为 0), 持久化侧按它做乱序防回写.
 */
interface TencentProactiveMessageSwitchEvent : TencentBotEvent {
  val subject: Contact
  val accept: Boolean
  val timestamp: Long
  val eventId: String
}

class TencentGroupMsgRejectEvent internal constructor(
  val operator: GroupMember,
  override val timestamp: Long,
  override val eventId: String,
) : TencentGroupEvent, TencentProactiveMessageSwitchEvent, TencentEvent() {
  override val group get() = operator.group
  override val subject get() = group
  override val accept get() = false
  override val bot get() = operator.bot
  override fun toString(): String {
    return "TencentGroupMsgRejectEvent(group=${group.id}, operator=${operator.id}, timestamp=$timestamp)"
  }
}

class TencentGroupMsgReceiveEvent internal constructor(
  val operator: GroupMember,
  override val timestamp: Long,
  override val eventId: String,
) : TencentGroupEvent, TencentProactiveMessageSwitchEvent, TencentEvent() {
  override val group get() = operator.group
  override val subject get() = group
  override val accept get() = true
  override val bot get() = operator.bot
  override fun toString(): String {
    return "TencentGroupMsgReceiveEvent(group=${group.id}, operator=${operator.id}, timestamp=$timestamp)"
  }
}

class TencentFriendMsgRejectEvent internal constructor(
  override val friend: FriendUser,
  override val timestamp: Long,
  override val eventId: String,
) : TencentFriendEvent, TencentProactiveMessageSwitchEvent, TencentEvent() {
  override val subject get() = friend
  override val accept get() = false
  override val bot get() = friend.bot
  override fun toString(): String {
    return "TencentFriendMsgRejectEvent(friend=${friend.id}, timestamp=$timestamp)"
  }
}

class TencentFriendMsgReceiveEvent internal constructor(
  override val friend: FriendUser,
  override val timestamp: Long,
  override val eventId: String,
) : TencentFriendEvent, TencentProactiveMessageSwitchEvent, TencentEvent() {
  override val subject get() = friend
  override val accept get() = true
  override val bot get() = friend.bot
  override fun toString(): String {
    return "TencentFriendMsgReceiveEvent(friend=${friend.id}, timestamp=$timestamp)"
  }
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
