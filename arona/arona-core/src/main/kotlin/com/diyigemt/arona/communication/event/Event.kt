@file:Suppress("UNUSED")

package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.*
import com.diyigemt.arona.communication.TencentBotAuthEndpointResp
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.contact.EmptyFriendUserImpl
import com.diyigemt.arona.communication.contact.EmptyMockGroupImpl
import com.diyigemt.arona.communication.contact.Guild.Companion.findOrCreateMemberPrivateChannel
import com.diyigemt.arona.communication.contact.GuildChannelMemberImpl
import com.diyigemt.arona.communication.contact.GuildMemberImpl
import com.diyigemt.arona.communication.message.*
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

// Sprint 2.1 清理: TencentWebsocketReadyHandler / TencentWebsocketResumedHandler 是 WebSocket 握手/恢复
// 专属, webhook 下发不会走这两条, 且已无 wsJob 启动路径. 删除这两个 handler 以收敛 dispatch map.

internal object TencentWebsocketMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentChannelMessageRaw, eventId: String) {
    val guild = ctx.bot.guilds.getOrCreate(payload.guildId)
    val tmp = GuildChannelMemberImpl(
      guild.channels.getOrCreate(payload.channelId),
      guild.members.getOrCreate(payload.author.id)
    )
    TencentGuildMessageEvent(payload.toMessageChain(), eventId, tmp).broadcast()
  }
}

/**
 * 频道@机器人消息
 */
internal object TencentWebsocketAtMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.AT_MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentChannelMessageRaw, eventId: String) {
    val guild = ctx.bot.guilds.getOrCreate(payload.guildId)
    val tmp = GuildChannelMemberImpl(
      guild.channels.getOrCreate(payload.channelId),
      guild.members.getOrCreate(payload.author.id)
    )
    TencentGuildMessageEvent(
      payload.toMessageChain(),
      eventId,
      tmp
    ).broadcast()
  }
}

// 频道私聊事件
internal object TencentWebsocketDirectMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.DIRECT_MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentChannelMessageRaw, eventId: String) {
    val memberId = payload.author.id
    val guildId = payload.guildId
    val guild = ctx.bot.guilds.getOrCreate(guildId)
    if (payload.member.user == null) {
      payload.member.user = payload.author
    }
    val tmp = GuildMemberImpl(
      guild,
      guild.findOrCreateMemberPrivateChannel(memberId, payload.channelId),
      payload.member
    )
    TencentGuildPrivateMessageEvent(
      payload.toMessageChain(),
      eventId,
      tmp
    ).broadcast()
  }
}

suspend fun mockGroupMessage(gid: String, uid: String, content: String, eid: String = "") {
  val bot = BotManager.getBot()
  val group = bot.groups.getOrCreate(gid) { EmptyMockGroupImpl(bot, gid) }
  val member = group.members.getOrCreate(uid)
  TencentGroupMessageEvent(PlainText(content).toMessageChain(), eid, member).broadcast()
}

internal object TencentWebsocketGroupAtMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentGroupMessageRaw>() {
  override val type = TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE
  override val decoder = TencentGroupMessageRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentGroupMessageRaw, eventId: String) {
    val member = ctx.bot.groups.getOrCreate(payload.groupId).members.getOrCreate(payload.author.id)
    TencentGroupMessageEvent(payload.toMessageChain(), eventId, member).broadcast()
  }
}

internal object TencentWebsocketC2CMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentFriendMessageRaw>() {
  override val type = TencentWebsocketEventType.C2C_MESSAGE_CREATE
  override val decoder = TencentFriendMessageRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentFriendMessageRaw, eventId: String) {
    TencentFriendMessageEvent(
      payload.toMessageChain(),
      eventId,
      ctx.bot.friends.getOrCreate(payload.author.id)
    ).broadcast()
  }
}

internal object TencentWebsocketGuildCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentGuildRaw>() {
  override val type = TencentWebsocketEventType.GUILD_CREATE
  override val decoder = TencentGuildRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentGuildRaw, eventId: String) {
    val guild = ctx.bot.guilds.getOrCreate(payload.id)
    val member = guild.members.getOrCreate(payload.opUserId)
    TencentGuildAddEvent(member, eventId).broadcast()
  }
}

internal object TencentWebsocketGroupAddBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotGroupEventRaw>() {
  override val type = TencentWebsocketEventType.GROUP_ADD_ROBOT
  override val decoder = TencentBotGroupEventRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentBotGroupEventRaw, eventId: String) {
    val group = ctx.bot.groups.getOrCreate(payload.id)
    val member = group.members.getOrCreate(payload.opMemberId)
    TencentGroupAddEvent(member, eventId).broadcast()
  }
}

internal object TencentWebsocketFriendAddBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotFriendEventRaw>() {
  override val type = TencentWebsocketEventType.FRIEND_ADD
  override val decoder = TencentBotFriendEventRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentBotFriendEventRaw, eventId: String) {
    val friend = ctx.bot.friends.getOrCreate(payload.id)
    TencentFriendAddEvent(friend, eventId).broadcast()
  }
}

internal object TencentWebsocketGroupDeleteBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotGroupEventRaw>() {
  override val type = TencentWebsocketEventType.GROUP_DEL_ROBOT
  override val decoder = TencentBotGroupEventRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentBotGroupEventRaw, eventId: String) {
    val group = ctx.bot.groups.getOrCreate(payload.id)
    val member = group.members.getOrCreate(payload.opMemberId)
    TencentGroupDeleteEvent(member, eventId).broadcast()
  }
}

internal object TencentWebsocketFriendDeleteBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotFriendEventRaw>() {
  override val type = TencentWebsocketEventType.FRIEND_DEL
  override val decoder = TencentBotFriendEventRaw.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentBotFriendEventRaw, eventId: String) {
    val friend = ctx.bot.friends.getOrCreate(payload.id)
    TencentFriendDeleteEvent(friend, eventId).broadcast()
  }
}

internal object TencentWebsocketCallbackButtonHandler : TencentWebsocketDispatchEventHandler<TencentWebsocketCallbackButtonResp>() {
  override val type = TencentWebsocketEventType.INTERACTION_CREATE
  override val decoder = TencentWebsocketCallbackButtonResp.serializer()

  override suspend fun handle(ctx: TencentDispatchContext, payload: TencentWebsocketCallbackButtonResp, eventId: String) {
    ctx.logger.debug("webhook receive callback btn from server.")
    ctx.logger.debug(payload.toString())
    payload.missingCallbackRouteField()?.let { field ->
      // ContactList 现为真缓存 (Sprint 1.2), 用 id="" 的 Empty 占位会永久驻留并污染后续路由,
      // 因此 payload 关键字段缺失直接短路, 不 broadcast, 也不触碰任何 ContactList.
      ctx.logger.warn(
        "skip callback button event: missing $field. " +
            "chatType=${payload.chatType}, interactionId=${payload.id}, eventId=$eventId"
      )
      return
    }
    val contact = when (payload.chatType) {
      TencentWebsocketCallbackButtonChatType.Guild -> {
        val guildId = payload.guildId!!
        val channelId = payload.channelId!!
        val userId = payload.data.resolved.userId!!
        ctx.bot.guilds.getOrCreate(guildId).let {
          it.channels.getOrCreate(channelId) to it.members.getOrCreate(userId)
        }
      }
      TencentWebsocketCallbackButtonChatType.Group -> {
        val groupOpenid = payload.groupOpenid!!
        val groupMemberOpenid = payload.groupMemberOpenid!!
        ctx.bot.groups.getOrCreate(groupOpenid).let {
          it to it.members.getOrCreate(groupMemberOpenid)
        }
      }
      TencentWebsocketCallbackButtonChatType.Friend -> {
        val userOpenId = payload.userOpenId!!
        ctx.bot.friends.getOrCreate(userOpenId).let {
          it to it
        }
      }
    }
    TencentCallbackButtonEvent(
      id = payload.id,
      internalId = eventId,
      appId = payload.applicationId,
      buttonId = payload.data.resolved.buttonId,
      buttonData = payload.data.resolved.buttonData ?: "",
      type = payload.type,
      chatType = payload.chatType,
      contact = contact.first,
      user = contact.second,
      bot = ctx.bot
    ).broadcast()
  }
}

/**
 * 返回 [payload] 在当前 [TencentWebsocketCallbackButtonResp.chatType] 下第一个缺失的必需字段名;
 * 全部合法则返回 `null`.
 *
 * 独立出纯函数以便直接单测, 无需构造 handler 依赖的完整 bot / dispatch context.
 */
internal fun TencentWebsocketCallbackButtonResp.missingCallbackRouteField(): String? = when (chatType) {
  TencentWebsocketCallbackButtonChatType.Guild -> when {
    guildId.isNullOrBlank() -> "guildId"
    channelId.isNullOrBlank() -> "channelId"
    // 事件模型 TencentCallbackButtonEvent.user: User 非空, 若用 id="" 兜底只会造 Empty 假 member 被 Sprint 1.2 的真缓存永久驻留.
    data.resolved.userId.isNullOrBlank() -> "resolved.userId"
    else -> null
  }
  TencentWebsocketCallbackButtonChatType.Group -> when {
    groupOpenid.isNullOrBlank() -> "groupOpenid"
    groupMemberOpenid.isNullOrBlank() -> "groupMemberOpenid"
    else -> null
  }
  TencentWebsocketCallbackButtonChatType.Friend -> when {
    userOpenId.isNullOrBlank() -> "userOpenId"
    else -> null
  }
}

/**
 * dispatch handler 的轻量上下文: 替代旧 `TencentBotClientWebSocketSession` 作为 receiver,
 * 只暴露 handler 真正依赖的 bot/logger/json, 切断 dispatch 路径与 WebSocket 会话的历史耦合.
 *
 * 形参是 [TencentBot] 接口而非具体 `TencentBotClient`, 让测试替身可以直接注入无需反射 backdoor.
 */
internal class TencentDispatchContext(val bot: TencentBot) {
  val logger get() = bot.logger
  val json get() = bot.json
}

/**
 * 把 handler 从 receiver-bound `TencentBotClientWebSocketSession.handleDispatchEvent` 改成
 * 普通参数方法, 消除 Sprint 3.2 前半的反射调用 (旧的 `handler::class.declaredFunctions...callSuspend`).
 * 子类显式 override [handle] 即可, 无需再走反射扫描或 KCallable.
 */
internal abstract class TencentWebsocketDispatchEventHandler<T> {
  abstract val type: TencentWebsocketEventType
  abstract val decoder: KSerializer<T>
  abstract suspend fun handle(ctx: TencentDispatchContext, payload: T, eventId: String = "")

  /**
   * 类型擦除桥接层: manager 解到的 payload 类型是 `Any?`, 由本方法在基类内部做一次受控 cast 调到 [handle].
   * cast 安全由 [decoder] 与 [T] 的一致性保证——`decoder: KSerializer<T>` 解出的结果必然是 T.
   */
  @Suppress("UNCHECKED_CAST")
  internal suspend fun handleDecoded(ctx: TencentDispatchContext, payload: Any?, eventId: String) {
    handle(ctx, payload as T, eventId)
  }
}

internal object TencentWebsocketDispatchEventManager {
  // 显式列表: 失去"自动扫描"的便利, 换来 dispatch 路径完全不依赖反射, 新增 handler 需显式登记于此.
  private val map: Map<TencentWebsocketEventType, TencentWebsocketDispatchEventHandler<*>> = listOf(
    TencentWebsocketMessageCreateHandler,
    TencentWebsocketAtMessageCreateHandler,
    TencentWebsocketDirectMessageCreateHandler,
    TencentWebsocketGroupAtMessageCreateHandler,
    TencentWebsocketC2CMessageCreateHandler,
    TencentWebsocketGuildCreateHandler,
    TencentWebsocketGroupAddBotHandler,
    TencentWebsocketFriendAddBotHandler,
    TencentWebsocketGroupDeleteBotHandler,
    TencentWebsocketFriendDeleteBotHandler,
    TencentWebsocketCallbackButtonHandler,
  ).associateBy { it.type }

  /** 测试侧用于断言 registry 覆盖面, 避免"新增 handler 忘记登记"静默退化为 noop. */
  internal fun registeredEventTypes(): Set<TencentWebsocketEventType> = map.keys

  internal suspend fun handleTencentDispatchEvent(
    ctx: TencentDispatchContext,
    event: TencentWebsocketEventType,
    source: String,
  ) {
    val handler = map[event] ?: return
    ctx.logger.debug("recev dispatch event: {}, data: {}", event, source)
    runCatching {
      ctx.json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), source)
    }.onSuccess {
      handler.handleDecoded(ctx, it.data, it.id ?: "")
    }.onFailure {
      ctx.logger.error(it)
      ctx.logger.error("decode dispatch event failed event: {}, data: {}", event, source)
    }
  }
}

interface Event

/**
 * 标记事件需要串行广播: 多个监听器按注册顺序依次执行, 而非并发.
 *
 * 用于监听器之间靠事件对象 (如 [com.diyigemt.arona.communication.event.MessagePreSendEvent.message]
 * 或 [com.diyigemt.arona.webui.event.ContentAuditEvent.pass]) 回传/改写结果的场景.
 * 没实现此接口的事件默认走并发分派, 监听器之间无执行顺序, 也不应并发写入同一事件对象.
 */
interface SerializedEvent : Event

abstract class AbstractEvent : Event

suspend fun <E : Event> E.broadcast(): E {
  EventChannelToEventDispatcherAdapter.instance.broadcastEventImpl(this)
  return this
}

abstract class TencentEvent : AbstractEvent() {
  abstract val bot: TencentBot
  abstract val eventId: String
  val logger get() = bot.logger
}

interface TencentBotEvent : Event {
  val bot: TencentBot
}

internal data class TencentBotAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentBotAuthEndpointResp,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}

internal data class TencentBotWebsocketHandshakeSuccessEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}

internal data class TencentBotWebsocketConnectionLostEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}

internal data class TencentBotWebsocketConnectionResumeEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}

internal data class TencentBotWebsocketAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentWebsocketIdentifyResp,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}

data class TencentBotOnlineEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId: String = ""
}
