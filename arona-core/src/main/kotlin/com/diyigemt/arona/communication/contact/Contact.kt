package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.utils.childScopeContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

interface Contact : CoroutineScope {
  val bot: TencentBot // 与之关联的bot
  val id: String // 特定id
  val unionOpenid: String? // 统一id

  /**
   * 被动回复消息, 消息体中需要带有messageId回执
   */
  suspend fun sendMessage(message: Message)

  /**
   * 主队回复消息, 消息体中可以没有messageId回执
   *
   * 每天能主动发的消息有限
   */
  suspend fun sendMessageActive(message: Message)
}

internal abstract class AbstractContact(
  final override val bot: TencentBot,
  parentCoroutineContext: CoroutineContext
): Contact {
  final override val coroutineContext: CoroutineContext = parentCoroutineContext.childScopeContext()
}

interface Guild : Contact

internal class GuildImpl (
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String? = null
): Guild, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) = sendMessageActive(message)

  override suspend fun sendMessageActive(message: Message) {
    TODO("Not yet implemented")
  }
}

interface Channel : Contact {
  val guild: Guild
}

internal class ChannelImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val guild: Guild,
  override val unionOpenid: String? = null
) : Channel, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) = sendMessageActive(message)
  override suspend fun sendMessageActive(message: Message) {
    TODO("Not yet implemented")
  }
}

interface Group : Contact

internal class GroupImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String? = null
) : Group, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) = sendMessageActive(message)
  override suspend fun sendMessageActive(message: Message) {
    TODO("Not yet implemented")
  }

}

interface User : Contact

interface SingleUser : User // 单纯用户 私聊情况下

// 群组成员 群聊情况下
interface GroupMember : User {
  /**
   * 所在群
   */
  val group: Group
  fun asSingleUser(): SingleUser
}

// 频道成员 频道聊天情况下
interface GuildMember : User {
  val channel: Channel
  val guild: Guild
  fun asGuildUser(): GuildUser
}
// 频道成员 私聊情况下
interface GuildUser : User {
  val guild: Guild
}
internal class SingleUserImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String?
) : SingleUser, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) {
    // TODO 主动发送消息
  }

  override suspend fun sendMessageActive(message: Message) {
    TODO("Not yet implemented")
  }
}

internal class GroupMemberImpl(
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val group: Group,
  override val unionOpenid: String? = null,
) : GroupMember, AbstractContact(group.bot, parentCoroutineContext) {
  override fun asSingleUser(): SingleUser {
    TODO("Not yet implemented")
  }

  override suspend fun sendMessage(message: Message) {
    TODO("Not yet implemented")
  }

  override suspend fun sendMessageActive(message: Message) = group.sendMessageActive(message)

}

internal class GuildMemberImpl(
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val guild: Guild,
  override val channel: Channel,
  override val unionOpenid: String? = null,
) : GuildMember, AbstractContact(channel.bot, parentCoroutineContext) {
  override fun asGuildUser(): GuildUser {
    TODO("Not yet implemented")
  }

  override suspend fun sendMessage(message: Message) {
    TODO("Not yet implemented")
  }

  override suspend fun sendMessageActive(message: Message) = channel.sendMessageActive(message)

}

internal class GuildUserImpl(
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val guild: Guild,
  override val unionOpenid: String? = null,
) : GuildUser, AbstractContact(guild.bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: Message) {
    TODO("Not yet implemented")
  }

  override suspend fun sendMessageActive(message: Message) {
    TODO("Not yet implemented")
  }
}

class ContactList<out C : Contact> constructor(
  @JvmField val delegate: MutableCollection<@UnsafeVariance C>
) : Collection<C> by delegate {
  constructor() : this(ConcurrentLinkedQueue())
  operator fun get(id: String): C? = delegate.firstOrNull { it.id == id }

  /**
   * 获取一个 [Contact.id] 为 [id] 的元素. 在不存在时抛出 [NoSuchElementException].
   */
  fun getOrFail(id: String): C = get(id) ?: throw NoSuchElementException("Contact $id not found.")

  /**
   * 删除 [Contact.id] 为 [id] 的元素.
   */
  fun remove(id: String): Boolean = delegate.removeAll { it.id == id }

  /**
   * 当存在 [Contact.id] 为 [id] 的元素时返回 `true`.
   */
  operator fun contains(id: String): Boolean = get(id) != null

  override fun toString(): String = delegate.joinToString(separator = ", ", prefix = "ContactList(", postfix = ")")
  override fun equals(other: Any?): Boolean = other is ContactList<*> && delegate == other.delegate
  override fun hashCode(): Int = delegate.hashCode()
}
