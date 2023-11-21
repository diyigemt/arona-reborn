@file:OptIn(ExperimentalContracts::class, ExperimentalContracts::class)

package com.diyigemt.arona.communication.command

import com.diyigemt.arona.AronaApplication
import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.TencentGuildMessageEvent
import com.diyigemt.arona.communication.event.TencentGuildPrivateMessageEvent
import com.diyigemt.arona.communication.event.TencentMessageEvent
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.childScope
import com.diyigemt.arona.utils.childScopeContext
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.qualifiedNameOrTip
import kotlinx.coroutines.CoroutineScope
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext

interface CommandSender : CoroutineScope {
  val bot: TencentBot?
  val subject: Contact?
  val user: User?
  val sourceId: String?
  var messageSequence: Int // 消息序列, 回复同一条sourceId时自增, 从1开始
  suspend fun sendMessage(message: String): MessageReceipt = sendMessage(PlainText(message))
  suspend fun sendMessage(message: Message): MessageReceipt

  companion object {
    fun TencentGuildMessageEvent.toCommandSender() = GuildChannelCommandSender(sender, message.sourceId)
    fun TencentGuildPrivateMessageEvent.toCommandSender() = GuildUserCommandSender(sender, message.sourceId)
    fun TencentGroupMessageEvent.toCommandSender() = GroupCommandSender(sender, message.sourceId)
    fun <T : TencentMessageEvent> T.toCommandSender() = when (this) {
      is TencentGuildMessageEvent -> toCommandSender()
      is TencentGuildPrivateMessageEvent -> toCommandSender()
      is TencentGroupMessageEvent -> toCommandSender()
      else -> throw IllegalArgumentException("Unsupported MessageEvent: ${this::class.qualifiedNameOrTip}")
    }

    fun SingleUser.asCommandSender() = SingleUserCommandSender(this, EmptyMessageId)
  }
}

sealed class AbstractCommandSender : CommandSender {
  abstract override val bot: TencentBot?
  abstract override val subject: Contact?
  abstract override val user: User?
}

sealed class AbstractUserCommandSender : UserCommandSender, AbstractCommandSender() {
  override val bot: TencentBot get() = user.bot
  override suspend fun sendMessage(message: Message) = user.sendMessage(message)
}

interface UserCommandSender : CommandSender {
  override val bot: TencentBot
  override val subject: Contact
  override val user: User
  override val sourceId: String
}

/**
 * 单聊
 */
class SingleUserCommandSender internal constructor(
  override val user: SingleUser,
  override val sourceId: String,
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("SingleUserCommandSender") {
  override val subject get() = user
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) = user.sendMessage(message.toMessageChain(sourceId), messageSequence).also { messageSequence++ }
}

/**
 * 群聊
 */
class GroupCommandSender internal constructor(
  override val user: GroupMember,
  override val sourceId: String,
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GroupCommandSender") {
  override val subject get() = user.group
  val group get() = user.group
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) = subject.sendMessage(message.toMessageChain(sourceId), messageSequence).also { messageSequence++ }
}

/**
 * 文字频道聊天
 */
class GuildChannelCommandSender internal constructor(
  override val user: GuildChannelMember,
  override val sourceId: String,
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GuildChannelCommandSender") {
  override val subject get() = user.channel
  val channel get() = user.channel
  val guild get() = user.guild
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) = subject.sendMessage(message.toMessageChain(sourceId), messageSequence).also { messageSequence++ }
}

/**
 * 通过频道发起的私聊
 */
class GuildUserCommandSender internal constructor(
  override val user: GuildMember,
  override val sourceId: String,
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GuildUserCommandSender") {
  override val subject get() = user.guild
  val guild get() = user.guild
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) = user.sendMessage(message.toMessageChain(sourceId), messageSequence).also { messageSequence++ }
}

object ConsoleCommandSender : AbstractCommandSender(), CommandSender {
  private const val NAME: String = "ConsoleCommandSender"
  override val bot: TencentBot? = null
  override val subject: Contact? = null
  override val user: User? = null
  override val sourceId: String = EmptyMessageId

  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message): MessageReceipt {
    commandLineLogger.info(message.serialization())
    TODO()
  }

  override val coroutineContext: CoroutineContext = AronaApplication.childScopeContext(NAME)
}

@OptIn(ExperimentalContracts::class)
fun CommandSender.isConsole(): Boolean {
  contract {
    returns(true) implies (this@isConsole is ConsoleCommandSender)
  }
  return this is ConsoleCommandSender
}

fun CommandSender.isNotConsole(): Boolean {
  contract {
    returns(true) implies (this@isNotConsole !is ConsoleCommandSender)
  }
  return this !is ConsoleCommandSender
}

fun CommandSender.isUser(): Boolean {
  contract {
    returns(true) implies (this@isUser is UserCommandSender)
  }
  return this is UserCommandSender
}

fun CommandSender.isNotUser(): Boolean {
  contract {
    returns(true) implies (this@isNotUser is ConsoleCommandSender)
  }
  return this !is UserCommandSender
}

inline fun <R> CommandSender.fold(
  ifIsConsole: ConsoleCommandSender.() -> R,
  ifIsUser: UserCommandSender.() -> R,
  otherwise: CommandSender.() -> R = { error("CommandSender ${this::class.qualifiedName} is not supported") },
): R {
  contract {
    callsInPlace(ifIsConsole, InvocationKind.AT_MOST_ONCE)
    callsInPlace(ifIsUser, InvocationKind.AT_MOST_ONCE)
    callsInPlace(otherwise, InvocationKind.AT_MOST_ONCE)
  }
  return when (val sender = this) {
    is ConsoleCommandSender -> ifIsConsole(sender)
    is UserCommandSender -> ifIsUser(sender)
    else -> otherwise(sender)
  }
}
