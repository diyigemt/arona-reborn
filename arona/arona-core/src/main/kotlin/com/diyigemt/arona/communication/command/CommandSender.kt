@file:OptIn(ExperimentalContracts::class, ExperimentalContracts::class, ExperimentalContracts::class)

package com.diyigemt.arona.communication.command

import com.diyigemt.arona.AronaApplication
import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.UserDocument.Companion.findUserDocumentByUidOrNull
import com.diyigemt.arona.database.service.ContactService
import com.diyigemt.arona.database.service.UserService
import com.diyigemt.arona.utils.childScope
import com.diyigemt.arona.utils.childScopeContext
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.qualifiedNameOrTip
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.full.starProjectedType

interface CommandSender : CoroutineScope {
  val bot: TencentBot?
  val subject: Contact?
  val user: User?
  val sourceId: String
  val eventId: String?
  /**
   * 消息序列, 回复同一条 sourceId 时自增, 从 1 开始.
   *
   * 仅保留 getter/setter 以避免已编译插件的 ABI 断裂; 内部发送路径请改走 [nextSequence] 获取原子递增值.
   * 直接写 `messageSequence++` 仍然是 read-modify-write, 无法避免并发 lost update.
   */
  var messageSequence: Int

  /**
   * 原子地取得当前序号并自增, 返回值就是本次发送应使用的 messageSequence.
   * 替代旧写法 `.sendMessage(..., messageSequence).also { messageSequence++ }` 中易丢增量的组合.
   */
  fun nextSequence(): Int

  suspend fun sendMessage(message: String) = sendMessage(PlainText(message))
  suspend fun sendMessage(message: Message): MessageReceipt<Contact>?

  companion object {
    fun TencentGuildMessageEvent.toCommandSender() = GuildChannelCommandSender(sender, message.sourceId)
    fun TencentGuildPrivateMessageEvent.toCommandSender() = GuildUserCommandSender(sender, message.sourceId)
    fun TencentGroupMessageEvent.toCommandSender() = GroupCommandSender(sender, message.sourceId)
    fun TencentFriendMessageEvent.toCommandSender() = FriendUserCommandSender(sender, message.sourceId)
    fun <T : TencentMessageEvent> T.toCommandSender() = when (this) {
      is TencentGuildMessageEvent -> toCommandSender()
      is TencentGuildPrivateMessageEvent -> toCommandSender()
      is TencentGroupMessageEvent -> toCommandSender()
      is TencentFriendMessageEvent -> toCommandSender()
      else -> throw IllegalArgumentException("Unsupported MessageEvent: ${this::class.qualifiedNameOrTip}")
    }

    fun FriendUser.asCommandSender() = FriendUserCommandSender(this, EmptyMessageId)
  }
}

sealed class AbstractCommandSender() : CommandSender {
  abstract override val bot: TencentBot?
  abstract override val subject: Contact?
  abstract override val user: User?
  private var _sourceId = EmptyMessageId
  override val sourceId: String
    get() = _sourceId
  internal val kType = this::class.starProjectedType

  /**
   * 真正的序列计数器, 由子类提供独立实例——各 sender 之间互不干扰, 同 sender 内用 atomic 保证并发正确.
   */
  protected abstract val messageSequenceRef: AtomicInteger
  final override var messageSequence: Int
    get() = messageSequenceRef.get()
    set(value) {
      messageSequenceRef.set(value)
    }
  final override fun nextSequence(): Int = messageSequenceRef.getAndIncrement()

  internal fun setSourceId(n: String) {
    _sourceId = n
  }

  constructor(sourceId: String) : this() {
    setSourceId(sourceId)
  }
}

sealed class AbstractUserCommandSender(sourceId: String) : UserCommandSender, AbstractCommandSender(sourceId) {
  private var _userDocument: UserDocument? = null
  private var _contactDocument: ContactDocument? = null
  override val bot: TencentBot get() = user.bot
  override suspend fun sendMessage(message: Message): MessageReceipt<Contact>? = user.sendMessage(message)
  override suspend fun userDocument(): PluginUserDocument {
    if (_userDocument == null) {
      _userDocument = findUserDocumentByUidOrNull(user.id) ?: UserService.createUser(user.id, subject.id)
    }
    return _userDocument!!
  }

  override suspend fun contactDocument(): PluginContactDocument {
    if (_contactDocument == null) {
      ContactService.createContactAndUser(subject, user, ContactRole.DEFAULT_MEMBER_CONTACT_ROLE_ID)
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
  override var sourceId: String
  suspend fun userDocument(): PluginUserDocument
  suspend fun contactDocument(): PluginContactDocument
  suspend fun contactMember(): PluginContactMember

  companion object {
    /**
     * 优先级 环境默认 > 用户分环境 > 用户默认
     */
    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readPluginConfigOrNull(plugin: CommandOwner) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfigOrNull<T>(plugin)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readPluginConfigOrDefault(
      plugin: CommandOwner,
      default: T
    ) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfigOrDefault<T>(plugin, default)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readPluginConfig(plugin: CommandOwner) =
      contactDocument().readPluginConfigOrNull<T>(plugin) ?: contactMember().readPluginConfigOrNull<T>(plugin)
      ?: userDocument().readPluginConfig<T>(plugin)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readUserPluginConfigOrNull(plugin: CommandOwner) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfigOrNull<T>(plugin)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readUserPluginConfigOrDefault(
      plugin: CommandOwner,
      default: T,
    ) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfigOrDefault<T>(plugin, default)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.readUserPluginConfig(plugin: CommandOwner) =
      contactMember().readPluginConfigOrNull<T>(plugin) ?: userDocument().readPluginConfig<T>(plugin)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.updateUserPluginConfig(
      plugin: CommandOwner,
      value: T
    ) =
      userDocument().updatePluginConfig<T>(plugin, value)

    suspend inline fun <reified T : PluginWebuiConfig> UserCommandSender.updateContactPluginConfig(
      plugin: CommandOwner,
      value: T
    ) =
      contactDocument().updatePluginConfig<T>(plugin, value)

  }
}


/**
 * 单聊
 */
class FriendUserCommandSender internal constructor(
  override val user: FriendUser,
  sourceId: String,
  override val eventId: String? = null,
) : AbstractUserCommandSender(sourceId), CoroutineScope by user.childScope("FriendUserCommandSender") {
  override val subject get() = user
  override val messageSequenceRef: AtomicInteger = AtomicInteger(1)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: Message): MessageReceipt<FriendUser>? =
    user.sendMessage(message.toMessageChain(sourceId, eventId), nextSequence()) as MessageReceipt<FriendUser>?
}

/**
 * 群聊
 */
class GroupCommandSender internal constructor(
  override val user: GroupMember,
  sourceId: String,
  override val eventId: String? = null,
) : AbstractUserCommandSender(sourceId), CoroutineScope by user.childScope("GroupCommandSender") {
  override val subject get() = user.group
  val group get() = user.group
  override val messageSequenceRef: AtomicInteger = AtomicInteger(1)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: Message): MessageReceipt<Group>? =
    subject.sendMessage(message.toMessageChain(sourceId, eventId), nextSequence()) as MessageReceipt<Group>?
}

/**
 * 文字频道聊天
 */
class GuildChannelCommandSender internal constructor(
  override val user: GuildChannelMember,
  sourceId: String,
  override val eventId: String? = null,
) : AbstractUserCommandSender(sourceId), CoroutineScope by user.childScope("GuildChannelCommandSender") {
  override val subject get() = user.channel
  val channel get() = user.channel
  val guild get() = user.guild
  override val messageSequenceRef: AtomicInteger = AtomicInteger(1)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: Message): MessageReceipt<Channel>? =
    subject.sendMessage(message.toMessageChain(sourceId, eventId), nextSequence()) as MessageReceipt<Channel>?
}

/**
 * 通过频道发起的私聊
 */
class GuildUserCommandSender internal constructor(
  override val user: GuildMember,
  sourceId: String,
  override val eventId: String? = null,
) : AbstractUserCommandSender(sourceId), CoroutineScope by user.childScope("GuildUserCommandSender") {
  override val subject get() = user.guild
  val guild get() = user.guild
  override val messageSequenceRef: AtomicInteger = AtomicInteger(1)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: Message): MessageReceipt<GuildMember>? =
    user.sendMessage(message.toMessageChain(sourceId, eventId), nextSequence()) as MessageReceipt<GuildMember>?
}

object ConsoleCommandSender : AbstractCommandSender(), CommandSender {
  private const val NAME: String = "ConsoleCommandSender"
  override val bot: TencentBot? = null
  override val subject: Contact? = null
  override val user: User? = null
  override val eventId: String? = null

  override val messageSequenceRef: AtomicInteger = AtomicInteger(1)
  override suspend fun sendMessage(message: Message): MessageReceipt<Contact>? {
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

fun CommandSender.isGuild() = !isGroupOrPrivate()

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

@OptIn(ExperimentalContracts::class)
fun CommandSender.isPrivateChannel(): Boolean {
  contract {
    returns(true) implies (this@isPrivateChannel is GuildUserCommandSender)
  }
  return this is GuildUserCommandSender
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
