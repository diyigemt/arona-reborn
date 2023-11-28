package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentBotAuthEndpointResp
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.Guild.Companion.findOrCreateMemberPrivateChannel
import com.diyigemt.arona.communication.contact.GuildChannelMemberImpl
import com.diyigemt.arona.communication.contact.GuildMemberImpl
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.database.guild.GuildMemberSchema
import com.diyigemt.arona.database.guild.GuildMemberTable
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.sql.and
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

@Suppress("UNUSED")
internal object TencentWebsocketReadyHandler : TencentWebsocketDispatchEventHandler<TencentWebsocketIdentifyResp>() {
  override val type = TencentWebsocketEventType.READY
  override val decoder = TencentWebsocketIdentifyResp.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentWebsocketIdentifyResp) {
    logger.info("websocket receive hello from server")
    sessionId = payload.sessionId
    TencentBotWebsocketAuthSuccessEvent(bot, payload).broadcast()
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw) {
    val guild = bot.guilds.getOrCreate(payload.guildId)
    val tmp = GuildChannelMemberImpl(
      guild.channels.getOrCreate(payload.channelId),
      guild.members.getOrCreate(payload.author.id)
    )
    TencentGuildMessageEvent(payload.toMessageChain(), tmp).broadcast()
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketAtMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.AT_MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw) {
    val guild = bot.guilds.getOrCreate(payload.guildId)
    val tmp = GuildChannelMemberImpl(
      guild.channels.getOrCreate(payload.channelId),
      guild.members.getOrCreate(payload.author.id)
    )
    TencentGuildMessageEvent(
      payload.toMessageChain(),
      tmp
    ).broadcast()
  }
}

// 频道私聊事件
@Suppress("UNUSED")
internal object TencentWebsocketDirectMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentChannelMessageRaw>() {
  override val type = TencentWebsocketEventType.DIRECT_MESSAGE_CREATE
  override val decoder = TencentChannelMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentChannelMessageRaw) {
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
      tmp
    ).broadcast()
  }
}

@Suppress("UNUSED")
internal object TencentWebsocketGroupAtMessageCreateHandler :
  TencentWebsocketDispatchEventHandler<TencentGroupMessageRaw>() {
  override val type = TencentWebsocketEventType.GROUP_AT_MESSAGE_CREATE
  override val decoder = TencentGroupMessageRaw.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentGroupMessageRaw) {
    val member = bot.groups.getOrCreate(payload.groupId).members.getOrCreate(payload.author.id)
    TencentGroupMessageEvent(payload.toMessageChain(), member).broadcast()
  }
}

internal abstract class TencentWebsocketDispatchEventHandler<T> {
  abstract val type: TencentWebsocketEventType
  abstract val decoder: KSerializer<T>
  abstract suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: T)
}

@Suppress("UNUSED")
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
      handler::class.declaredFunctions.firstOrNull()?.callSuspend(handler, this, it.data)
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
  val logger get() = bot.logger
}

interface TencentBotEvent : Event {
  val bot: TencentBot
}

internal data class TencentBotAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentBotAuthEndpointResp,
) : TencentBotEvent, TencentEvent()

internal data class TencentBotWebsocketHandshakeSuccessEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent()

internal data class TencentBotWebsocketConnectionLostEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent()

internal data class TencentBotWebsocketConnectionResumeEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent()

internal data class TencentBotWebsocketAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentWebsocketIdentifyResp,
) : TencentBotEvent, TencentEvent()

data class TencentBotOnlineEvent(
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent()
