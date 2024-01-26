@file:OptIn(ExperimentalContracts::class, ExperimentalContracts::class, ExperimentalContracts::class)

package com.diyigemt.arona.communication.command

import com.diyigemt.arona.AronaApplication
import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.TencentGuildMessageEvent
import com.diyigemt.arona.communication.event.TencentGuildPrivateMessageEvent
import com.diyigemt.arona.communication.event.TencentMessageEvent
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQuerySuspended
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.database.permission.UserDocument.Companion.createUserDocument
import com.diyigemt.arona.database.permission.UserDocument.Companion.findUserDocumentByUidOrNull
import com.diyigemt.arona.utils.*
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.qualifiedNameOrTip
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.serializer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.full.starProjectedType

interface CommandSender : CoroutineScope {
  val bot: TencentBot?
  val subject: Contact?
  val user: User?
  val sourceId: String?
  val eventId: String?
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

    fun FriendUser.asCommandSender() = FriendUserCommandSender(this, EmptyMessageId)
  }
}

sealed class AbstractCommandSender : CommandSender {
  abstract override val bot: TencentBot?
  abstract override val subject: Contact?
  abstract override val user: User?
  internal val kType = this::class.starProjectedType
}

sealed class AbstractUserCommandSender : UserCommandSender, AbstractCommandSender() {
  private var _userDocument: UserDocument? = null
  private var _contactDocument: ContactDocument? = null
  override val bot: TencentBot get() = user.bot
  override suspend fun sendMessage(message: Message) = user.sendMessage(message)
  override suspend fun userDocument(): PluginUserDocument {
    if (_userDocument == null) {
      _userDocument = findUserDocumentByUidOrNull(user.id) ?: createUserDocument(user.id, subject.id)
    }
    return _userDocument!!
  }

  override suspend fun contactDocument(): PluginContactDocument {
    if (_contactDocument == null) {
      ContactDocument.createContactAndUser(subject, user, ContactRole.DEFAULT_MEMBER_CONTACT_ROLE_ID)
      _contactDocument = ContactDocument.findContactDocumentByIdOrNull(subject.fatherSubjectIdOrSelf)
    }
    return _contactDocument!!
  }

  override suspend fun contactMember(): PluginContactMember {
    return contactDocument().findContactMember(userDocument().id)
  }
}

interface UserCommandSender : CommandSender {
  override val bot: TencentBot
  override val subject: Contact
  override val user: User
  override val sourceId: String
  suspend fun userDocument(): PluginUserDocument
  suspend fun contactDocument(): PluginContactDocument
  suspend fun contactMember(): PluginContactMember

  companion object {
    /**
     * 优先级 环境默认 > 用户分环境 > 用户默认
     */
    suspend inline fun <reified T : Any> UserCommandSender.readPluginConfigOrNull(plugin: CommandOwner) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfigOrNull<T>(plugin)

    suspend inline fun <reified T : Any> UserCommandSender.readPluginConfigOrDefault(plugin: CommandOwner, default: T) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfigOrDefault<T>(plugin, default)

    suspend inline fun <reified T : Any> UserCommandSender.readPluginConfig(plugin: CommandOwner) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfig<T>(plugin)

    suspend inline fun <reified T : Any> UserCommandSender.readUserPluginConfigOrNull(plugin: CommandOwner) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfigOrNull<T>(plugin)

    suspend inline fun <reified T : Any> UserCommandSender.readUserPluginConfigOrDefault(plugin: CommandOwner, default: T) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfigOrDefault<T>(plugin, default)

    suspend inline fun <reified T : Any> UserCommandSender.readUserPluginConfig(plugin: CommandOwner) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfig<T>(plugin)
    suspend inline fun <reified T : Any> UserCommandSender.updateUserPluginConfig(plugin: CommandOwner, value: T) =
      userDocument().updatePluginConfig<T>(plugin, value)
    suspend inline fun <reified T : Any> UserCommandSender.updateContactPluginConfig(plugin: CommandOwner, value: T) =
      contactDocument().updatePluginConfig<T>(plugin, value)

  }
}


/**
 * 单聊
 */
class FriendUserCommandSender internal constructor(
  override val user: FriendUser,
  override val sourceId: String,
  override val eventId: String? = null
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("FriendUserCommandSender") {
  override val subject get() = user
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) =
    user.sendMessage(message.toMessageChain(sourceId, eventId), messageSequence).also { messageSequence++ }
}

/**
 * 群聊
 */
class GroupCommandSender internal constructor(
  override val user: GroupMember,
  override val sourceId: String,
  override val eventId: String? = null
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GroupCommandSender") {
  override val subject get() = user.group
  val group get() = user.group
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) =
    subject.sendMessage(message.toMessageChain(sourceId, eventId), messageSequence).also { messageSequence++ }
}

/**
 * 文字频道聊天
 */
class GuildChannelCommandSender internal constructor(
  override val user: GuildChannelMember,
  override val sourceId: String,
  override val eventId: String? = null
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GuildChannelCommandSender") {
  override val subject get() = user.channel
  val channel get() = user.channel
  val guild get() = user.guild
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) =
    subject.sendMessage(message.toMessageChain(sourceId, eventId), messageSequence).also { messageSequence++ }
}

/**
 * 通过频道发起的私聊
 */
class GuildUserCommandSender internal constructor(
  override val user: GuildMember,
  override val sourceId: String,
  override val eventId: String? = null
) : AbstractUserCommandSender(), CoroutineScope by user.childScope("GuildUserCommandSender") {
  override val subject get() = user.guild
  val guild get() = user.guild
  override var messageSequence: Int = 1
  override suspend fun sendMessage(message: Message) =
    user.sendMessage(message.toMessageChain(sourceId, eventId), messageSequence).also { messageSequence++ }
}

object ConsoleCommandSender : AbstractCommandSender(), CommandSender {
  private const val NAME: String = "ConsoleCommandSender"
  override val bot: TencentBot? = null
  override val subject: Contact? = null
  override val user: User? = null
  override val sourceId: String = EmptyMessageId
  override val eventId: String? = null

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

// TODO 支持私聊
@OptIn(ExperimentalContracts::class)
fun CommandSender.isGroupOrPrivate(): Boolean {
  contract {
    returns(true) implies (this@isGroupOrPrivate is GroupCommandSender)
  }
  return this is GroupCommandSender
}

@OptIn(ExperimentalContracts::class)
fun CommandSender.isGroup(): Boolean {
  contract {
    returns(true) implies (this@isGroup is GroupCommandSender)
  }
  return this is GroupCommandSender
}

@OptIn(ExperimentalContracts::class)
fun CommandSender.isPrivate(): Boolean {
  contract {
    returns(true) implies (this@isPrivate is FriendUserCommandSender)
  }
  return this is FriendUserCommandSender
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
