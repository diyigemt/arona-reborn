package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.GuildChannelMemberImpl
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
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
    val tmp = GuildChannelMemberImpl(
      coroutineContext,
      bot.guilds[payload.guildId]!!.channels[payload.channelId]!!,
      bot.guilds[payload.guildId]!!.members[payload.author.id]!!
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
    val tmp = GuildChannelMemberImpl(
      coroutineContext,
      bot.guilds[payload.guildId]!!.channels[payload.channelId]!!,
      bot.guilds[payload.guildId]!!.members[payload.author.id]!!
    )
    TencentGuildMessageEvent(
      payload.toMessageChain().let { MessageChainBuilder(it).append(TencentAt(bot)).build() },
      tmp
    ).broadcast()
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

abstract class AbstractEvent

suspend fun <E : AbstractEvent> E.broadcast(): E {
  EventChannelToEventDispatcherAdapter.instance.broadcastEventImpl(this)
  return this
}

abstract class TencentEvent : AbstractEvent() {
  abstract val bot: TencentBot
  val logger get() = bot.logger
}

internal data class TencentBotWebsocketHandshakeSuccessEvent(override val bot: TencentBot) : TencentEvent()

internal data class TencentBotWebsocketConnectionLostEvent(override val bot: TencentBot) : TencentEvent()

internal data class TencentBotWebsocketAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentWebsocketIdentifyResp,
) : TencentEvent()

data class TencentBotOnlineEvent(
  override val bot: TencentBot,
) : TencentEvent()
