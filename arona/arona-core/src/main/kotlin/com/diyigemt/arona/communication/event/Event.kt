@file:Suppress("UNUSED")

package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.*
import com.diyigemt.arona.communication.TencentBotAuthEndpointResp
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.Guild.Companion.findOrCreateMemberPrivateChannel
import com.diyigemt.arona.communication.contact.GuildChannelMemberImpl
import com.diyigemt.arona.communication.contact.GuildMemberImpl
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

internal object TencentWebsocketReadyHandler : TencentWebsocketDispatchEventHandler<TencentWebsocketIdentifyResp>() {
  override val type = TencentWebsocketEventType.READY
  override val decoder = TencentWebsocketIdentifyResp.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentWebsocketIdentifyResp, eventId: String) {
    logger.info("websocket receive hello from server")
    sessionId = payload.sessionId
    TencentBotWebsocketAuthSuccessEvent(bot, payload).broadcast()
  }
}

internal object TencentWebsocketMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw, eventId: String) {
    val guild = bot.guilds.getOrCreate(payload.guildId)
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

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw, eventId: String) {
    val guild = bot.guilds.getOrCreate(payload.guildId)
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

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw, eventId: String) {
    val memberId = payload.author.id
    val guildId = payload.guildId
    val guild = bot.guilds.getOrCreate(guildId)
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

internal object TencentWebsocketGroupAtMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentGroupMessageRaw>() {
  override val type = TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE
  override val decoder = TencentGroupMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentGroupMessageRaw, eventId: String) {
    val member = bot.groups.getOrCreate(payload.groupId).members.getOrCreate(payload.author.id)
    TencentGroupMessageEvent(payload.toMessageChain(), eventId, member).broadcast()
  }
}

internal object TencentWebsocketGuildCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentGuildRaw>() {
  override val type = TencentWebsocketEventType.GUILD_CREATE
  override val decoder = TencentGuildRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentGuildRaw, eventId: String) {
    val guild = bot.guilds.getOrCreate(payload.id)
    val member = guild.members.getOrCreate(payload.opUserId)
    TencentGuildAddEvent(member, eventId).broadcast()
  }
}

internal object TencentWebsocketGroupAddBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotGroupEventRaw>() {
  override val type = TencentWebsocketEventType.GROUP_ADD_ROBOT
  override val decoder = TencentBotGroupEventRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentBotGroupEventRaw, eventId: String) {
    val group = bot.groups.getOrCreate(payload.id)
    val member = group.members.getOrCreate(payload.opMemberId)
    TencentGroupAddEvent(member, eventId).broadcast()
  }
}

internal object TencentWebsocketFriendAddBotHandler :
  TencentWebsocketDispatchEventHandler<TencentBotFriendEventRaw>() {
  override val type = TencentWebsocketEventType.FRIEND_ADD
  override val decoder = TencentBotFriendEventRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentBotFriendEventRaw, eventId: String) {
    val friend = bot.friends.getOrCreate(payload.id)
    TencentFriendAddEvent(friend, eventId).broadcast()
  }
}

internal object TencentWebsocketCallbackButtonHandler : TencentWebsocketDispatchEventHandler<TencentWebsocketCallbackButtonResp>() {
  override val type = TencentWebsocketEventType.INTERACTION_CREATE
  override val decoder = TencentWebsocketCallbackButtonResp.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentWebsocketCallbackButtonResp, eventId: String) {
    logger.debug("websocket receive callback btn from server.")
    logger.debug(payload.toString())
    val contact = when (payload.chatType) {
      TencentWebsocketCallbackButtonChatType.Guild -> {
        bot.guilds.getOrCreate(payload.guildId ?: "").let {
          it.channels.getOrCreate(payload.channelId ?: "").let { ch ->
            ch to it.members.getOrCreate(payload.data.resolved.userId ?: "")
          }
        }
      }
      TencentWebsocketCallbackButtonChatType.Group -> {
        bot.groups.getOrCreate(payload.groupOpenid ?: "").let {
          it to it.members.getOrCreate(payload.groupMemberOpenid ?: "")
        }
      }
      TencentWebsocketCallbackButtonChatType.Friend -> {
        bot.friends.getOrCreate(payload.userOpenId ?: "").let {
          it to it
        }
      }
    }
    TencentCallbackButtonEvent(
      id = payload.id,
      appId = payload.applicationId,
      buttonId = payload.data.resolved.buttonId,
      buttonData = payload.data.resolved.buttonData ?: "",
      type = payload.type,
      chatType = payload.chatType,
      contact = contact.first,
      user = contact.second,
      bot = bot
    ).broadcast()
  }
}

internal abstract class TencentWebsocketDispatchEventHandler<T> {
  abstract val type: TencentWebsocketEventType
  abstract val decoder: KSerializer<T>
  abstract suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: T, eventId: String = "")
}

internal object TencentWebsocketDispatchEventManager {
  private val map by lazy {
    ReflectionUtil.scanInterfacePetObjectInstance(TencentWebsocketDispatchEventHandler::class).associateBy { it.type }
  }

  internal suspend fun TencentBotClientWebSocketSession.handleTencentDispatchEvent(
    event: TencentWebsocketEventType,
    source: String,
  ) {
    val handler = map[event] ?: return
    logger.debug("recev dispatch event: {}, data: {}", event, source)
    runCatching {
      json.decodeFromString(TencentWebsocketPayload.serializer(handler.decoder), source)
    }.onSuccess {
      handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, it.data, it.id ?: "")
    }.onFailure {
      logger.error(it)
      logger.error("decode dispatch event failed event: {}, data: {}", event, source)
    }
  }
}

interface Event

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
