package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.childScopeContext
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

interface Contact : CoroutineScope {
  val bot: TencentBot // 与之关联的bot
  val id: String // 特定id
  val unionOpenid: String? // 统一id
  val unionOpenidOrId
    get() = unionOpenid ?: id
  suspend fun sendMessage(message: String) = sendMessage(PlainText(message))

  /**
   * 被动回复消息, 消息体中需要带有messageId回执
   */
  suspend fun sendMessage(message: Message) = sendMessage(message.toMessageChain())

  /**
   * 回复消息, 如果包含messageId就是被动消息, 不是就是主动消息
   */
  suspend fun sendMessage(message: MessageChain): MessageReceipt
}

internal abstract class AbstractContact(
  final override val bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
) : Contact {
  final override val coroutineContext: CoroutineContext = parentCoroutineContext.childScopeContext()
  suspend fun callMessageOpenApi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String> = mapOf(),
    body: MessageChain,
  ): MessageReceipt {
    return bot.callOpenapi(
      endpoint,
      MessageReceipt.serializer(),
      urlPlaceHolder
    ) {
      method = HttpMethod.Post
      contentType(ContentType.Application.Json)
      setBody(
        "{\"image\":\"https://arona.cdn.diyigemt.com/image/some/长草.png\",\"msg_id\":\"${body.sourceId}\"}"
      )
    }.getOrThrow() // TODO 异常处理
  }
}

interface Guild : Contact {
  val members: ContactList<GuildMember>
  val channels: ContactList<Channel>
  val emptyGuildMember : GuildMember
  val isPublic: Boolean
}

internal class GuildImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  private val internalGuild: TencentGuildRaw,
) : Guild, AbstractContact(bot, parentCoroutineContext) {
  override val id get() = internalGuild.id
  override val unionOpenid: String? = null
  override val members: ContactList<GuildMember> = ContactList()
  override val channels: ContactList<Channel> = ContactList()
  override val emptyGuildMember : GuildMember = EmptyGuildMemberImpl(this)
  override val isPublic: Boolean = bot.isPublic
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    // 无法实现
    TODO()
  }

  init {
    this.launch {
      if (!isPublic) fetchMemberList()
      fetchChannelList()
    }
  }

  private suspend fun fetchMemberList() {
    bot.callOpenapi(
      TencentEndpoint.GetGuildMemberList,
      ListSerializer(TencentGuildMemberRaw.serializer()),
      mapOf("guild_id" to id)
    ) {
      method = HttpMethod.Get
      url {
        parameters.append("limit", "400")
      }
    }.onSuccess {
      members.delegate.addAll(
        it.map { member ->
          GuildMemberImpl(
            this@GuildImpl,
            member
          )
        }
      )
    }
  }

  private suspend fun fetchChannelList() {
    bot.callOpenapi(
      TencentEndpoint.GetGuildChannelList,
      ListSerializer(TencentGuildChannelRaw.serializer()),
      mapOf("guild_id" to id)
    ) {
      method = HttpMethod.Get
    }.onSuccess {
      channels.delegate.addAll(
        it.map { ch ->
          ChannelImpl(
            bot,
            coroutineContext,
            this@GuildImpl,
            ch
          )
        }
      )
    }
  }
}

interface Channel : Contact {
  val guild: Guild
}

internal class ChannelImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val guild: Guild,
  private val internalChannel: TencentGuildChannelRaw,
) : Channel, AbstractContact(bot, parentCoroutineContext) {
  override val id get() = internalChannel.id
  override val unionOpenid: String? = null
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    return callMessageOpenApi(
      TencentEndpoint.PostGuildMessage,
      mapOf("channel_id" to id),
      message
    )
  }
}

interface Group : Contact

internal class GroupImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String? = null,
) : Group, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    return callMessageOpenApi(
      TencentEndpoint.PostGroupMessage,
      mapOf("group_openid" to id),
      message
    )
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
interface GuildChannelMember : User {
  val channel: Channel
  val guild: Guild
  fun asGuildMember(): GuildMember
}

// 频道成员 私聊情况下
interface GuildMember : User {
  val guild: Guild
}

internal class SingleUserImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String?,
) : SingleUser, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    return callMessageOpenApi(
      TencentEndpoint.PostSingleUserMessage,
      mapOf("openid" to id),
      message
    )
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

  override suspend fun sendMessage(message: MessageChain) = asSingleUser().sendMessage(message)
}

internal class GuildChannelMemberImpl(
  parentCoroutineContext: CoroutineContext,
  override val channel: Channel,
  private val internalMember: GuildMember,
) : GuildChannelMember, AbstractContact(channel.bot, parentCoroutineContext) {
  override val id get() = internalMember.id
  override val guild get() = internalMember.guild
  override val unionOpenid: String? = null
  override fun asGuildMember(): GuildMember = channel.guild.members[id]!!
  override suspend fun sendMessage(message: MessageChain) = asGuildMember().sendMessage(message)
}

internal class GuildMemberImpl(
  override val guild: Guild,
  private val internalGuildUser: TencentGuildMemberRaw,
  override val unionOpenid: String? = null,
) : GuildMember, AbstractContact(guild.bot, guild.coroutineContext) {
  override val id get() = unionOpenid ?: internalGuildUser.user?.id ?: EmptyMessageId
  override suspend fun sendMessage(message: MessageChain): MessageReceipt {
    // 保存私聊频道才行
    TODO("Not yet implemented")
  }
}

internal class EmptyGuildMemberImpl(
  override val guild: Guild,
) : GuildMember, AbstractContact(guild.bot, guild.coroutineContext) {
  override val unionOpenid = EmptyMessageId
  override val id = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain): MessageReceipt = guild.sendMessage(message)
}

class ContactList<out C : Contact>(
  internal val delegate: MutableCollection<@UnsafeVariance C>,
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
