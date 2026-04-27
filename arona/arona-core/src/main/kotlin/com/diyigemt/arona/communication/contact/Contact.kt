package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.MessageDuplicationException
import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.contact.Guild.Companion.batchResolveMemberPrivateChannels
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
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalListener
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.and
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

// Sprint 3.5(c): 旧 GuildImpl.init 直接调 fetchMemberList()/fetchChannelList(), 这两个函数返回 Unit
// 还吃掉了 callOpenapi 的 Result, init 协程一旦 fetch 失败 (常见于 Tencent API 5xx / 网络抖动) 就永久
// 缺数据, 没有任何重试. 把 fetch* 改返回 Result 让失败信号显式化, init 走 retryInitFetch 做指数退避.
//
// 实现要点:
//  - 总尝试 = 1 initial + 3 retry = 4 次, 间隔 1s/3s/9s. 中间失败静默 (callOpenapi 内部已 log onFailure),
//    末次失败再 warn 一次, 不放大日志噪音.
//  - CancellationException 必须直通: bot scope cancel 后 init 不能被 retry 兜住继续跑.
//  - 一切非 CE 异常 (含 fetch lambda 内部 sqlDbQuery 抛) 都被转成 Result.failure, 进入下一轮 retry.
internal val GuildInitFetchBackoff: LongArray = longArrayOf(1_000L, 3_000L, 9_000L)

internal suspend fun <T> retryInitFetch(
  logger: Logger,
  label: String,
  delays: LongArray = GuildInitFetchBackoff,
  fetch: suspend () -> Result<T>,
): Result<T> {
  var lastFailure: Throwable? = null
  for (attempt in 0..delays.size) {
    val result = try {
      fetch()
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }
    if (result.isSuccess) return result
    lastFailure = result.exceptionOrNull()
    if (attempt < delays.size) {
      delay(delays[attempt])
    }
  }
  logger.warn("init fetch failed after ${delays.size + 1} attempts, step=$label", lastFailure)
  return Result.failure(lastFailure ?: IllegalStateException("init fetch failed without cause: $label"))
}

/**
 * 命中 [com.diyigemt.arona.communication.MessageDuplicationException] 后计算重放用的 messageSequence.
 *
 * 只偏移 `current + 1..4`——腾讯 msg_seq 空间较小, 大跨度偏移会落到合法序列之外直接报错, 或撞上同 sender
 * 已经分配出去的下一条消息的序号. 这是一个止血方案; 要做到并发下严格不冲突, 需要把 sender 的原子序号分配器
 * 沿调用链透传给 retry 路径.
 *
 * 独立成函数以便单测其边界 (反证: 回退到 `current + (100..1000).random()` 时测试必失败).
 */
internal fun computeMessageDuplicationRetrySequence(current: Int): Int = current + Random.nextInt(1, 5)

interface Contact : CoroutineScope {
  val bot: TencentBot // 与之关联的bot
  val id: String // 特定id
  val unionOpenid: String? // 统一id, bot则为appid
  val unionOpenidOrId
    get() = unionOpenid.takeIf { !it.isNullOrEmpty() } ?: id
  val fatherSubjectIdOrSelf
    get() = if (this is Channel) this.guild.id else id

  /**
   * 是否拥有独立的 [CoroutineScope] / [Job].
   *
   * `true` (默认): 该 Contact 在构造时通过 [com.diyigemt.arona.utils.childScopeContext] 派生出独立
   * SupervisorJob, 释放时需主动 cancel——典型如 [GuildImpl] / [GroupImpl] / [FriendUserImpl] /
   * [ChannelImpl] 这类容器型实体, 自身有 init 任务或承载 inner [ContactList].
   *
   * `false` (借用模式): 该 Contact 直接复用父 [CoroutineContext], **不**持有自己的 [Job]——典型如
   * 频道消息/私聊路径上每条消息 new 出来的 leaf member (`GuildChannelMemberImpl` /
   * `GuildMemberImpl` / `GroupMemberImpl` 及其 Empty 占位). 这些对象只是身份/转发视图, 没有需要
   * 跟随实例独立 cancel 的副作用. 借用模式下 [ContactList.remove] 等回收路径必须避免误 cancel
   * 父 scope, 否则会让一整棵 Guild/Group 的子任务一起死掉.
   */
  val ownsCoroutineScope: Boolean
    get() = true

  suspend fun sendMessage(message: String, messageSequence: Int = 1) = sendMessage(PlainText(message), messageSequence)

  /**
   * 被动回复消息, 消息体中需要带有messageId回执
   */
  suspend fun sendMessage(message: Message, messageSequence: Int = 1) =
    sendMessage(message.toMessageChain(), messageSequence)

  /**
   * 回复消息, 如果包含messageId或eventId就是被动消息, 不是就是主动消息
   */
  suspend fun sendMessage(message: MessageChain, messageSequence: Int = 1): MessageReceipt<Contact>?

  suspend fun uploadImage(url: String): TencentImage

  suspend fun uploadImage(data: ByteArray): TencentImage

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
  ownsCoroutineScope: Boolean = true,
) : Contact {
  final override val ownsCoroutineScope: Boolean = ownsCoroutineScope
  // ownsCoroutineScope=true 时派生独立 SupervisorJob, false 时直接复用父 context.
  // 借用模式必须不建新 Job, 否则父 children 链表会按实例数线性增长 (典型即每条消息泄漏的临时 sender).
  final override val coroutineContext: CoroutineContext =
    if (ownsCoroutineScope) parentCoroutineContext.childScopeContext()
    else parentCoroutineContext

  @Suppress("UNCHECKED_CAST")
  suspend fun <C : Contact> callMessageOpenApi(
    endpoint: TencentEndpoint,
    urlPlaceHolder: Map<String, String> = mapOf(),
    body: MessageChain,
    messageSequence: Int,
    preSendEventConstructor: (C, Message) -> MessagePreSendEvent,
    postSendEventConstructor: (C, MessageChain, Throwable?, MessageReceipt<C>?) -> MessagePostSendEvent<C>,
  ): MessageReceipt<C>? {
    val target = this as C

    // pre-send 分两步: 构造事件 + 广播. 构造或广播任一环节抛异常, 都要当作 "pre-send 阶段失败"
    // 仍向下游补发一次 post-send, 保证 pre/post 成对语义 (破坏会让审计/限流/回执类 listener 感知不到失败).
    val preResult = kotlin.runCatching {
      preSendEventConstructor(target, body).broadcast()
    }.onFailure {
      // 协作式取消必须向上透传, 否则调用方误以为发送正常结束继续走 post-send 并 return null.
      if (it is CancellationException) throw it
      commandLineLogger.error(it)
    }
    val chain = preResult.getOrNull()?.message?.toMessageChain()
    if (chain == null) {
      // 失败分支: message 传原始 body (未被 listener 改写过的版本), exception 透传真实原因.
      // post-send 本身再抛异常不应改变 pre-send 失败的主结果, 用 runCatching 兜底; 取消例外透传.
      kotlin.runCatching {
        postSendEventConstructor(target, body, preResult.exceptionOrNull(), null).broadcast()
      }.onFailure {
        if (it is CancellationException) throw it
        commandLineLogger.error(it)
      }
      return null
    }

    suspend fun send(messageSequence: Int): Result<MessageReceiptImpl> {
      val builder = TencentMessageBuilder(messageSequence = messageSequence).append(chain)
      return if (this is Group || this is FriendUser) {
        bot.callOpenapi(
          endpoint,
          MessageReceiptImpl.serializer(),
          urlPlaceHolder
        ) {
          method = HttpMethod.Post
          // TODO 支持其他类型消息
          contentType(ContentType.Application.Json)
          setBody(
            bot.json.encodeToString(
              builder.build(this@AbstractContact is GuildMember)
            )
          )
        }
      } else {
        bot.callOpenapi(
          endpoint,
          MessageReceiptImpl.serializer(),
          urlPlaceHolder
        ) {
          method = HttpMethod.Post
          // TODO 支持其他类型消息
          // 包含本地图片 改用 form-data 发送
          if (chain.any { it is TencentGuildLocalImage }) {
            contentType(ContentType.MultiPart.FormData)
            setBody(
              builder.buildMultipart()
            )
          } else {
            contentType(ContentType.Application.Json)
            setBody(
              bot.json.encodeToString(
                builder.build(this@AbstractContact is GuildMember)
              )
            )
          }
        }
      }
    }
    var result = send(messageSequence)
    if (result.exceptionOrNull() is MessageDuplicationException) {
      // 止血式重放: 腾讯 msg_seq 空间较小, 旧实现的 +100..1000 偏移极易越界或撞到别的合法序列.
      // 真正避免与同 sender 的并发序列冲突, 要把 retry 序列分配器从上层透传下来 (待后续任务).
      result = send(computeMessageDuplicationRetrySequence(messageSequence))
    }
    val res = result.getOrNull()?.toMessageReceipt() as MessageReceipt<C>?
    // 成功与失败统一 broadcast post-send; 广播自身异常仅记录, 不污染 send 主语义的返回值; 取消例外透传.
    kotlin.runCatching {
      postSendEventConstructor(
        target,
        chain,
        result.exceptionOrNull(),
        res
      ).broadcast()
    }.onFailure {
      if (it is CancellationException) throw it
      commandLineLogger.error(it)
    }

    return res
  }

  override suspend fun uploadImage(
    url: String,
  ): TencentImage {
    return when (this) {
      is FriendUser -> {
        val cache = bot.callOpenapi(
          TencentEndpoint.PostFriendRichMessage,
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
        }
        cache
          .getOrNull()?.let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, url) } ?: bot
          .client
          .get(url)
          .let {
            if (it.status == HttpStatusCode.OK) {
              uploadImage(it.readBytes())
            } else {
              throw cache.exceptionOrNull()!!
            }
          }
      }

      is Group -> {
        val cache = bot.callOpenapi(
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
        }
        cache
          .getOrNull()?.let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, url) } ?: bot
          .client
          .get(url)
          .let {
            if (it.status == HttpStatusCode.OK) {
              uploadImage(it.readBytes())
            } else {
              throw cache.exceptionOrNull()!!
            }
          }
      }

      else -> TencentGuildImage(url)
    }
  }

  @OptIn(ExperimentalEncodingApi::class)
  override suspend fun uploadImage(
    data: ByteArray,
  ): TencentImage {
    val base64Encoded = Base64.encode(data)
    return when (this) {
      is FriendUser -> {
        bot.callOpenapi(
          TencentEndpoint.PostFriendRichMessage,
          TencentMessageMediaInfo.serializer(),
          mapOf("openid" to this.id)
        ) {
          method = HttpMethod.Post
          setBody(
            bot.json.encodeToString(
              TencentRichMessage(
                srvSendMsg = false,
                fileData = base64Encoded
              )
            )
          )
        }.getOrThrow()
          .let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, getMediaUrlFromMediaInfo(it.fileInfo)) }
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
                srvSendMsg = false,
                fileData = base64Encoded
              )
            )
          )
        }.getOrThrow()
          .let { TencentOfflineImage(it.fileInfo, it.fileUuid, it.ttl, getMediaUrlFromMediaInfo(it.fileInfo)) }
      }

      else -> TencentGuildLocalImage(raw = data)
    }
  }
}

interface Guild : Contact {
  val members: ContactList<GuildMember>
  val channels: ContactList<Channel>
  val isPublic: Boolean

  companion object {
    /**
     * 批量解析 [memberIds] 在当前 guild 下已有的私聊 channelId 映射 (单次 SQL `IN (...)` 查询).
     *
     * 仅查询不写入: [GuildImpl.fetchMemberList] 启动期预热成员表的路径默认 channelId="0", 不会触发
     * `findOrCreateMemberPrivateChannel` 的 insert 分支, 因此整体批量化只需消除 N 次同步查询.
     * 需要 insert 的懒路径仍走单条 [findOrCreateMemberPrivateChannel].
     */
    fun Guild.batchResolveMemberPrivateChannels(memberIds: Collection<String>): Map<String, String> {
      val distinctIds = memberIds.asSequence().filter { it.isNotEmpty() }.distinct().toList()
      if (distinctIds.isEmpty()) return emptyMap()
      return sqlDbQuery {
        GuildMemberSchema.find {
          (GuildMemberTable.botId eq bot.id) and
            (GuildMemberTable.guildId eq id) and
            (GuildMemberTable.id inList distinctIds)
        }.associate { it.id.value to it.channelId }
      }
    }

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
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Guild>? {
    // Guild 本身不是消息接收方; 腾讯 API 要求发送到具体 Channel.
    throw UnsupportedOperationException("Guild cannot send messages directly. Send via a concrete Channel instead.")
  }

  init {
    this.launch {
      if (!isPublic) {
        retryInitFetch(bot.logger, "guild $id members") { fetchMemberList() }
        retryInitFetch(bot.logger, "guild $id channels") { fetchChannelList() }
      }
    }
  }

  private suspend fun fetchMemberList(): Result<List<TencentGuildMemberRaw>> {
    return bot.callOpenapi(
      TencentEndpoint.GetGuildMemberList,
      ListSerializer(TencentGuildMemberRaw.serializer()),
      mapOf("guild_id" to id)
    ) {
      method = HttpMethod.Get
      url {
        parameters.append("limit", "400")
      }
    }.onSuccess { rawMembers ->
      // 单次批量查询替代逐成员的 N 次同步 SQL (limit=400 → 原本启动期单 guild 400 次 sqlDbQuery).
      val channelIdByMemberId = batchResolveMemberPrivateChannels(
        rawMembers.mapNotNull { it.user?.id }
      )
      rawMembers.forEach { member ->
        val memberId = member.user?.id ?: ""
        members.getOrCreate(memberId) {
          GuildMemberImpl(
            this@GuildImpl,
            channels.getOrCreate(channelIdByMemberId[memberId] ?: "0"),
            member
          )
        }
      }
    }
  }

  private suspend fun fetchChannelList(): Result<List<TencentGuildChannelRaw>> {
    return bot.callOpenapi(
      TencentEndpoint.GetGuildChannelList,
      ListSerializer(TencentGuildChannelRaw.serializer()),
      mapOf("guild_id" to id)
    ) {
      method = HttpMethod.Get
    }.onSuccess {
      it.forEach { ch ->
        channels.getOrCreate(ch.id) {
          ChannelImpl(
            bot,
            this@GuildImpl,
            ch
          )
        }
      }
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

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Channel>? {
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
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Group>? {
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
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<FriendUser>? {
    return callMessageOpenApi(
      TencentEndpoint.PostFriendMessage,
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
) : GroupMember, AbstractContact(group.bot, parentCoroutineContext, ownsCoroutineScope = false) {
  // Sprint 1.2 后 ContactList 是真缓存, friends.getOrCreate(id) 同 id 稳定返回同一 FriendUser 实例,
  // 重复调用不会重复创建或互相覆盖.
  override fun asSingleUser(): FriendUser = bot.friends.getOrCreate(id)

  override suspend fun uploadImage(url: String) = asSingleUser().uploadImage(url)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<FriendUser>? =
    asSingleUser().sendMessage(message) as? MessageReceipt<FriendUser>?
}

internal class GuildChannelMemberImpl(
  override val channel: Channel,
  private val internalMember: GuildMember,
) : GuildChannelMember, AbstractContact(channel.bot, channel.coroutineContext, ownsCoroutineScope = false) {
  override val id get() = internalMember.id
  override val guild get() = internalMember.guild
  override val unionOpenid: String? = null
  override fun asGuildMember(): GuildMember = channel.guild.members[id]!!

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<GuildMember>? =
    asGuildMember().sendMessage(message) as? MessageReceipt<GuildMember>?
}

// 通过频道直接获取的频道成员
internal class GuildMemberImpl(
  override val guild: Guild,
  override val channel: Channel, // 私聊频道
  private val internalGuildUser: TencentGuildMemberRaw,
  override val unionOpenid: String? = null,
) : GuildMember, AbstractContact(guild.bot, guild.coroutineContext, ownsCoroutineScope = false) {
  override val id get() = unionOpenid ?: internalGuildUser.user?.id ?: EmptyMessageId

  // 私聊使用另一个接口, 而不是频道接口
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<GuildMember>? {
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
) : GuildMember, EmptyContact, AbstractContact(guild.bot, guild.coroutineContext, ownsCoroutineScope = false) {
  override val unionOpenid = EmptyMessageId
  override val channel: Channel
    get() = guild.findOrCreateMemberPrivateChannel(id)

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Channel>? =
    channel.sendMessage(message) as? MessageReceipt<Channel>?
}

internal class EmptyGuildChannelMemberImpl(
  override val channel: Channel, // 私聊频道
  override val id: String = EmptyMessageId,
) : GuildChannelMember, EmptyContact, AbstractContact(channel.bot, channel.coroutineContext, ownsCoroutineScope = false) {
  override val guild: Guild = channel.guild
  override fun asGuildMember(): GuildMember = guild.members.getOrCreate(id)
  override val unionOpenid = EmptyMessageId

  @Suppress("unchecked_cast")
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Channel>? =
    channel.sendMessage(message) as? MessageReceipt<Channel>?
}

internal class EmptyChannelImpl(
  override val guild: Guild,
  override val id: String = EmptyMessageId,
) : Channel, EmptyContact, AbstractContact(guild.bot, guild.coroutineContext) {
  override val unionOpenid = EmptyMessageId
  override val members: ContactList<GuildChannelMember> =
    GuildChannelMemberContactList { EmptyGuildChannelMemberImpl(this, it) }

  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Channel>? {
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
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Guild>? {
    throw IllegalStateException("EmptyGuildImpl is a placeholder (id=$id) and cannot send messages. It should be replaced by a real Guild via getOrCreate(id, factory) before use.")
  }
}

internal class EmptyGroupMemberImpl(
  override val group: Group,
  override val id: String = EmptyMessageId,
) : GroupMember, EmptyContact, AbstractContact(group.bot, group.coroutineContext, ownsCoroutineScope = false) {
  override val unionOpenid: String = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<GroupMember>? {
    throw IllegalStateException("EmptyGroupMemberImpl is a placeholder (id=$id) and cannot send messages.")
  }

  override fun asSingleUser(): FriendUser =
    throw IllegalStateException("EmptyGroupMemberImpl is a placeholder (id=$id) and cannot resolve a single user.")
}

internal class EmptyMockGroupMemberImpl(
  override val group: Group,
  override val id: String = EmptyMessageId,
) : GroupMember, EmptyContact, AbstractContact(group.bot, group.coroutineContext, ownsCoroutineScope = false) {
  override val unionOpenid: String = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<GroupMember>? {
    throw IllegalStateException("EmptyMockGroupMemberImpl is a placeholder (id=$id) and cannot send messages.")
  }

  override fun asSingleUser(): FriendUser =
    throw IllegalStateException("EmptyMockGroupMemberImpl is a placeholder (id=$id) and cannot resolve a single user.")
}

internal class EmptyFriendUserImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : FriendUser, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<FriendUser>? {
    return callMessageOpenApi(
      TencentEndpoint.PostFriendMessage,
      mapOf("openid" to id),
      message,
      messageSequence,
      ::FriendMessagePreSendEvent,
      ::FriendMessagePostSendEvent
    )
  }
}

internal class EmptyGroupImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : Group, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override val members: ContactList<GroupMember> = GroupMemberContactList { EmptyGroupMemberImpl(this, it) }
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Group>? {
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

internal class EmptyMockGroupImpl(
  bot: TencentBot,
  override val id: String = EmptyMessageId,
) : Group, EmptyContact, AbstractContact(bot, bot.coroutineContext) {
  override val unionOpenid: String = EmptyMessageId
  override val members: ContactList<GroupMember> = GroupMemberContactList { EmptyMockGroupMemberImpl(this, it) }
  override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Group>? {
    return MessageReceipt(MessageReceiptImpl("", ""), this)
  }
}

/**
 * Caffeine 后端 [ConcurrentMap], 用于顶层 [ContactList] 的有界缓存语义.
 *
 * 淘汰回调仅 cancel `ownsCoroutineScope == true` 的 Contact 自身 SupervisorJob; 借用父 scope 的
 * leaf member 不能在这里 cancel, 否则会误伤父 Guild/Group/Channel 的 Job. 容器型 Contact (Guild/
 * Group/Channel) 的 inner ContactList 会随 outer Job cancel + 强引用断开自然释放, 此处不显式 clear,
 * 避免淘汰瞬间还在被 listener 路径访问引发并发歧义.
 *
 * `removalListener` 默认在 Caffeine executor (ForkJoinPool.commonPool) 异步执行, cancel SupervisorJob
 * 是 cheap 操作可以接受异步语义; 改用 `evictionListener` (维护线程同步执行) 会阻塞 cache 维护路径,
 * 不划算.
 */
internal fun <C : Contact> boundedContactMap(
  maximumSize: Long,
  expireAfterAccessSeconds: Long,
): ConcurrentMap<String, C> {
  val removalListener = RemovalListener<String, C> { _, contact, _ ->
    if (contact?.ownsCoroutineScope == true) {
      contact.coroutineContext[Job]?.cancel()
    }
  }
  return Caffeine.newBuilder()
    .maximumSize(maximumSize)
    .expireAfterAccess(expireAfterAccessSeconds, TimeUnit.SECONDS)
    .removalListener(removalListener)
    .build<String, C>()
    .asMap()
}

abstract class ContactList<out C : Contact>(
  internal val delegate: ConcurrentMap<String, @UnsafeVariance C>,
  val generator: (id: String) -> @UnsafeVariance C,
) : Collection<C> by delegate.values {
  constructor(generator: (id: String) -> @UnsafeVariance C) : this(ConcurrentHashMap(), generator)

  constructor(
    maximumSize: Long,
    expireAfterAccessSeconds: Long,
    generator: (id: String) -> @UnsafeVariance C,
  ) : this(boundedContactMap(maximumSize, expireAfterAccessSeconds), generator)

  operator fun get(id: String): C? = delegate[id]

  fun getOrCreate(id: String): C = delegate.computeIfAbsent(id) { generator(it) }

  /**
   * 允许调用方传入富对象 factory 用来升级占位对象.
   *
   * 事件路径会先用默认 [generator] 缓存 [EmptyContact] 占位; 之后像 `fetchGuildList`
   * 那种补全接口应通过此重载把缓存替换为富对象, 并取消被替换掉的占位 scope, 避免 Empty 永久占用.
   * 已经是富对象的条目不会被重复替换.
   */
  fun getOrCreate(
    id: String,
    factory: (String) -> @UnsafeVariance C,
  ): C = delegate.compute(id) { key, existing ->
    when {
      existing == null -> factory(key)
      existing is EmptyContact -> {
        // 借用父 scope 的占位 cancel 等于 cancel 父 Guild/Group, 必须跳过.
        if (existing.ownsCoroutineScope) {
          existing.coroutineContext[Job]?.cancel()
        }
        factory(key)
      }
      else -> existing
    }
  }!!

  fun getOrFail(id: String): C = delegate[id] ?: throw NoSuchElementException("Contact $id not found.")

  fun remove(id: String): Boolean {
    val removed = delegate.remove(id) ?: return false
    // 仅拥有独立 scope 的 Contact 需要主动 cancel; 借用父 scope 的实例直接 cancel 等于
    // 把父 Guild/Group 杀掉, 引发整棵子树连锁 cancel, 不是这里的预期语义.
    if (removed.ownsCoroutineScope) {
      removed.coroutineContext[Job]?.cancel()
    }
    return true
  }

  operator fun contains(id: String): Boolean = delegate.containsKey(id)

  override fun toString(): String =
    delegate.values.joinToString(separator = ", ", prefix = "ContactList(", postfix = ")")

  override fun equals(other: Any?): Boolean = other is ContactList<*> && delegate == other.delegate
  override fun hashCode(): Int = delegate.hashCode()
}

internal class GuildMemberContactList(
  generator: (id: String) -> GuildMember,
) : ContactList<GuildMember>(generator)

internal class GuildChannelMemberContactList(
  generator: (id: String) -> GuildChannelMember,
) : ContactList<GuildChannelMember>(generator)

internal class ChannelContactList(
  generator: (id: String) -> Channel,
) : ContactList<Channel>(generator)

internal class GuildContactList : ContactList<Guild> {
  constructor(generator: (id: String) -> Guild) : super(generator)
  constructor(maximumSize: Long, expireAfterAccessSeconds: Long, generator: (id: String) -> Guild) :
    super(maximumSize, expireAfterAccessSeconds, generator)
}

internal class GroupContactList : ContactList<Group> {
  constructor(generator: (id: String) -> Group) : super(generator)
  constructor(maximumSize: Long, expireAfterAccessSeconds: Long, generator: (id: String) -> Group) :
    super(maximumSize, expireAfterAccessSeconds, generator)
}

internal class GroupMemberContactList(
  generator: (id: String) -> GroupMember,
) : ContactList<GroupMember>(generator)

internal class SingleUserContactList : ContactList<FriendUser> {
  constructor(generator: (id: String) -> FriendUser) : super(generator)
  constructor(maximumSize: Long, expireAfterAccessSeconds: Long, generator: (id: String) -> FriendUser) :
    super(maximumSize, expireAfterAccessSeconds, generator)
}
