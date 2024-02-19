package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.contact.Guild.Companion.findOrCreateMemberPrivateChannel
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider.sqlDbQuery
import com.diyigemt.arona.database.guild.GuildMemberSchema
import com.diyigemt.arona.database.guild.GuildMemberTable
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.utils.childScopeContext
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.error
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.and
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

interface Contact : CoroutineScope {
  val bot: TencentBot // 与之关联的bot
  val id: String // 特定id
  val unionOpenid: String? // 统一id
  val unionOpenidOrId
    get() = unionOpenid ?: id
  val fatherSubjectIdOrSelf
    get() = if (this is Channel) this.guild.id else id

  suspend fun sendMessage(message: String, messageSequence: Int = 1) = sendMessage(PlainText(message), messageSequence)

  /**
   * 被动回复消息, 消息体中需要带有messageId回执
   */
  suspend fun sendMessage(message: Message, messageSequence: Int = 1) =
    sendMessage(message.toMessageChain(), messageSequence)

  /**
   * 回复消息, 如果包含messageId或eventId就是被动消息, 不是就是主动消息
   */
  suspend fun sendMessage(message: MessageChain, messageSequence: Int = 1): MessageReceipt?

  suspend fun uploadImage(url: String): TencentImage

  companion object {
    internal suspend fun Contact.toContactDocumentOrNull(): ContactDocument? {
      return when (this) {
        is Group, is Guild -> {
          findContactDocumentByIdOrNull(id)
        }

        is Channel -> {
          findContactDocumentByIdOrNull(guild.id)
        }

        else -> null
      }
    }
  }
}

internal abstract class AbstractContact(
  final override val bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
) : Contact {
  final override val coroutineContext: CoroutineContext = parentCoroutineContext.childScopeContext()
  @Suppress("UNCHECKED_CAST")
  suspend fun <C : Contact> callMessageOpenApi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String> = mapOf(),
    body: MessageChain,
    messageSequence: Int,
    preSendEventConstructor: (C, Message) -> MessagePreSendEvent,
    postSendEventConstructor: (C, MessageChain, Throwable?, MessageReceipt?) -> MessagePostSendEvent<C>,
    ): MessageReceipt? {
    // 消息处理

    val chain = kotlin.runCatching {
      preSendEventConstructor(this as C, body).broadcast()
    }.onFailure {
      commandLineLogger.error(it)
    }.getOrNull()?.message?.toMessageChain() ?: return null

    val result = bot.callOpenapi(
      endpoint,
      MessageReceipt.serializer(),
      urlPlaceHolder
    ) {
      method = HttpMethod.Post
      // TODO 支持其他类型消息
      contentType(ContentType.Application.Json)
      setBody(
        bot.json.encodeToString(
          TencentMessageBuilder(messageSequence = messageSequence).append(chain).build(this@AbstractContact is GuildMember)
        )
      )
    }
    postSendEventConstructor(this as C, chain, result.exceptionOrNull(), result.getOrNull()).broadcast()

    // TODO 异常处理
    return result.getOrNull()
  }

  override suspend fun uploadImage(
    url: String,
  ): TencentImage {
    return when (this) {
      is FriendUser -> {
        bot.callOpenapi(
          TencentEndpoint.PostSingleUserRichMessage,
          TencentMessageMediaInfo.serializer(),
          mapOf("openid" to this.id)
        ) {
          method = HttpMethod.Post
          setBody(
            bot.json.encodeToString(
              TencentRichMessage(
                url = url,
                srvSendMsg = false
              )
            )
          )
        }.getOrThrow().let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, url) }
      }

      is Group -> {
        bot.callOpenapi(
          TencentEndpoint.PostGroupRichMessage,
          TencentMessageMediaInfo.serializer(),
          mapOf("group_openid" to this.id)
        ) {
          method = HttpMethod.Post
          setBody(
            bot.json.encodeToString(
              TencentRichMessage(
                url = url,
                srvSendMsg = false
              )
            )
          )
        }.getOrThrow().let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, url) }
      }

      else -> TencentGuildImage(url)
    }
  }
}

interface Guild : Contact {
  val members: ContactList<GuildMember>
  val channels: ContactList<Channel>
  val isPublic: Boolean

  companion object {
    fun Guild.findOrCreateMemberPrivateChannel(memberId: String, channelId: String = "0"): Channel {
      return when (val channel = sqlDbQuery {
        GuildMemberSchema.find {
          (GuildMemberTable.botId eq bot.id) and (GuildMemberTable.id eq memberId) and (GuildMemberTable.guildId eq id)
        }.firstOrNull()
      }) {
        is GuildMemberSchema -> {
          channels.getOrCreate(
            channel.channelId
          )

        }

        else -> {
          channels.getOrCreate(
            channelId
          ).also {
            if (channelId != "0") {
              // 记录私聊频道
              sqlDbQuery {
                GuildMemberSchema.new(memberId) {
                  this@new.botId = bot.id
                  this@new.guildId = this@findOrCreateMemberPrivateChannel.id
                  this@new.channelId = channelId
                }
              }
            }
          }
        }
      }
    }
  }
}

internal class GuildImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  private val internalGuild: TencentGuildRaw,
) : Guild, AbstractContact(bot, parentCoroutineContext) {
  override val id get() = internalGuild.id
  override val unionOpenid: String? = null
  override val members: ContactList<GuildMember> = GuildMemberContactList { EmptyGuildMemberImpl(this, it) }
  override val channels: ContactList<Channel> = ChannelContactList { EmptyChannelImpl(this, it) }
  override val isPublic: Boolean = bot.isPublic
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
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
            findOrCreateMemberPrivateChannel(member.user?.id ?: ""),
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
  val members: ContactList<GuildChannelMember>
}

internal class ChannelImpl(
  bot: TencentBot,
  override val guild: Guild,
  private val internalChannel: TencentGuildChannelRaw,
) : Channel, AbstractContact(bot, guild.coroutineContext) {
  override val id get() = internalChannel.id
  override val unionOpenid: String? = null
  override val members: ContactList<GuildChannelMember> =
    GuildChannelMemberContactList { EmptyGuildChannelMemberImpl(this, it) }

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostGuildMessage,
      mapOf("channel_id" to id),
      message,
      messageSequence,
      ::ChannelMessagePreSendEvent,
      ::ChannelMessagePostSendEvent
    )
  }
}

interface Group : Contact {
  val members: ContactList<GroupMember>
}

internal class GroupImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String? = null,
) : Group, AbstractContact(bot, parentCoroutineContext) {
  override val members: ContactList<GroupMember> = GroupMemberContactList { EmptyGroupMemberImpl(this, it) }
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostGroupMessage,
      mapOf("group_openid" to id),
      message,
      messageSequence,
      ::GroupMessagePreSendEvent,
      ::GroupMessagePostSendEvent,
    )
  }
}

interface User : Contact {
  companion object {
    internal suspend fun User.toUserDocumentOrNull() = UserDocument.findUserDocumentByUidOrNull(id)
  }
}

interface FriendUser : User // 单纯用户 私聊情况下

// 群组成员 群聊情况下
interface GroupMember : User {
  /**
   * 所在群
   */
  val group: Group
  fun asSingleUser(): FriendUser
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
  val channel: Channel
}

internal class FriendUserImpl(
  bot: TencentBot,
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val unionOpenid: String?,
) : FriendUser, AbstractContact(bot, parentCoroutineContext) {
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostSingleUserMessage,
      mapOf("openid" to id),
      message,
      messageSequence,
      ::FriendMessagePreSendEvent,
      ::FriendMessagePostSendEvent
    )
  }
}

internal class GroupMemberImpl(
  parentCoroutineContext: CoroutineContext,
  override val id: String,
  override val group: Group,
  override val unionOpenid: String? = null,
) : GroupMember, AbstractContact(group.bot, parentCoroutineContext) {
  override fun asSingleUser(): FriendUser {
    TODO("Not yet implemented")
  }

  override suspend fun uploadImage(url: String) = asSingleUser().uploadImage(url)
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? = asSingleUser().sendMessage(message)
}

internal class GuildChannelMemberImpl(
  override val channel: Channel,
  private val internalMember: GuildMember,
) : GuildChannelMember, AbstractContact(channel.bot, channel.coroutineContext) {
  override val id get() = internalMember.id
  override val guild get() = internalMember.guild
  override val unionOpenid: String? = null
  override fun asGuildMember(): GuildMember = channel.guild.members[id]!!
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? = asGuildMember().sendMessage(message)
}

// 通过频道直接获取的频道成员
internal class GuildMemberImpl(
  override val guild: Guild,
  override val channel: Channel, // 私聊频道
  private val internalGuildUser: TencentGuildMemberRaw,
  override val unionOpenid: String? = null,
) : GuildMember, AbstractContact(guild.bot, guild.coroutineContext) {
  override val id get() = unionOpenid ?: internalGuildUser.user?.id ?: EmptyMessageId

  // 私聊使用另一个接口, 而不是频道接口
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostGuildMemberMessage,
      mapOf("guild_id" to guild.id),
      message,
      messageSequence,
      ::GuildMessagePreSendEvent,
      ::GuildMessagePostSendEvent
    )
  }
}

interface EmptyContact : Contact {
  override val id: String
    get() = EmptyMessageId
}

internal class EmptyGuildMemberImpl(
  override val guild: Guild,
  override val id: String = EmptyMessageId,
) : GuildMember, EmptyContact, AbstractContact(guild.bot, guild.coroutineContext) {
  override val unionOpenid = EmptyMessageId
  override val channel: Channel
    get() = guild.findOrCreateMemberPrivateChannel(id)

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? = channel.sendMessage(
    message
  )
}

internal class EmptyGuildChannelMemberImpl(
  override val channel: Channel, // 私聊频道
  override val id: String = EmptyMessageId,
) : GuildChannelMember, EmptyContact, AbstractContact(channel.bot, channel.coroutineContext) {
  override val guild: Guild = channel.guild
  override fun asGuildMember(): GuildMember = guild.members.getOrCreate(id)
  override val unionOpenid = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? = channel.sendMessage(
    message
  )
}

internal class EmptyChannelImpl(
  override val guild: Guild,
  override val id: String = EmptyMessageId,
) : Channel, EmptyContact, AbstractContact(guild.bot, guild.coroutineContext) {
  override val unionOpenid = EmptyMessageId
  override val members: ContactList<GuildChannelMember> =
    GuildChannelMemberContactList { EmptyGuildChannelMemberImpl(this, it) }

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostGuildMessage,
      mapOf("channel_id" to id),
      message,
      messageSequence,
      ::ChannelMessagePreSendEvent,
      ::ChannelMessagePostSendEvent
    )
  }
}

internal class EmptyGuildImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : Guild, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override val members: ContactList<GuildMember> = GuildMemberContactList { EmptyGuildMemberImpl(this, it) }
  override val channels: ContactList<Channel> = ChannelContactList { EmptyChannelImpl(this, it) }
  override val isPublic: Boolean = bot.isPublic
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    TODO("Not yet implemented")
  }
}

internal class EmptyGroupMemberImpl(
  override val group: Group,
  override val id: String = EmptyMessageId,
) : GroupMember, EmptyContact, AbstractContact(group.bot, group.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    TODO("Not yet implemented")
  }

  override fun asSingleUser(): FriendUser {
    TODO("Not yet implemented")
  }
}

internal class EmptyFriendUserImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : FriendUser, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    TODO("Not yet implemented")
  }
}

internal class EmptyGroupImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : Group, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override val members: ContactList<GroupMember> = GroupMemberContactList { EmptyGroupMemberImpl(this, it) }
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt? {
    return callMessageOpenApi(
      TencentEndpoint.PostGroupMessage,
      mapOf("group_openid" to id),
      message,
      messageSequence,
      ::GroupMessagePreSendEvent,
      ::GroupMessagePostSendEvent,
    )
  }
}

abstract class ContactList<out C : Contact>(
  internal val delegate: MutableCollection<@UnsafeVariance C>,
) : Collection<C> by delegate {
  constructor() : this(ConcurrentLinkedQueue())

  operator fun get(id: String): C? = delegate.firstOrNull { it.id == id }

  abstract val generator: (id: String) -> C

  fun getOrCreate(id: String): C = get(id) ?: generator(id).also { delegate.add(it) }

  fun getOrFail(id: String): C = get(id) ?: throw NoSuchElementException("Contact $id not found.")

  fun remove(id: String): Boolean = delegate.removeAll { it.id == id }

  operator fun contains(id: String): Boolean = get(id) != null

  override fun toString(): String = delegate.joinToString(separator = ", ", prefix = "ContactList(", postfix = ")")
  override fun equals(other: Any?): Boolean = other is ContactList<*> && delegate == other.delegate
  override fun hashCode(): Int = delegate.hashCode()
}

internal class GuildMemberContactList(
  override val generator: (id: String) -> GuildMember,
) : ContactList<GuildMember>()

internal class GuildChannelMemberContactList(
  override val generator: (id: String) -> GuildChannelMember,
) : ContactList<GuildChannelMember>()

internal class ChannelContactList(
  override val generator: (id: String) -> Channel,
) : ContactList<Channel>()

internal class GuildContactList(
  override val generator: (id: String) -> Guild,
) : ContactList<Guild>()

internal class GroupContactList(
  override val generator: (id: String) -> Group,
) : ContactList<Group>()


internal class GroupMemberContactList(
  override val generator: (id: String) -> GroupMember,
) : ContactList<GroupMember>()

internal class SingleUserContactList(
  override val generator: (id: String) -> FriendUser,
) : ContactList<FriendUser>()
