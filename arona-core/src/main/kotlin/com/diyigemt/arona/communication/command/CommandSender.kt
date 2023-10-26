@file:OptIn(ExperimentalContracts::class)

package com.diyigemt.arona.communication.command

import com.diyigemt.arona.AronaApplication
import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.utils.childScopeContext
import kotlinx.coroutines.CoroutineScope
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext

interface CommandSender : CoroutineScope {
  val bot: TencentBot?
  val subject: Contact?
  val user: User?
  companion object {

  }
}

sealed class AbstractCommandSender : CommandSender {
  abstract override val bot: TencentBot?
  abstract override val subject: Contact?
  abstract override val user: User?
}

interface UserCommandSender : CommandSender {
  override val bot: TencentBot
  override val subject: Contact
  override val user: User
}

interface SingleUserCommandSender : UserCommandSender {
  override val user: SingleUser
  override val subject: SingleUser
}

interface GroupCommandSender : CommandSender {
  override val user: GroupMember
  override val subject: Group
}

interface GuildChannelCommandSender : CommandSender {
  override val user: GuildMember
  override val subject: Channel
}

interface GuildUserCommandSender : CommandSender {
  override val user: GuildUser
  override val subject: Guild
}

object ConsoleCommandSender : AbstractCommandSender(), CommandSender {
  private const val NAME: String = "ConsoleCommandSender"
  override val bot: TencentBot? = null
  override val subject: Contact? = null
  override val user: User? = null
  override val coroutineContext: CoroutineContext = AronaApplication.childScopeContext(NAME)
}

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
