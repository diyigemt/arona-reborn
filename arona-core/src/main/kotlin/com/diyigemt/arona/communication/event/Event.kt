package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.ReflectionUtil
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
internal object TencentWebsocketMessageCreateHandler : TencentWebsocketDispatchEventHandler<TencentGuildMessage>() {
  override val type = TencentWebsocketEventType.MESSAGE_CREATE
  override val decoder = TencentGuildMessage.serializer()

  override suspend fun TencentBotClientWebSocketSession.handleDispatchEvent(payload: TencentGuildMessage) {
    TencentGuildMessageEvent(MessageChainImpl(payload), null).broadcast()
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
    source: String
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

suspend fun <E : AbstractEvent> E.broadcast(): E {
  EventChannelToEventDispatcherAdapter.instance.broadcastEventImpl(this)
  return this
}

@Serializable
enum class TencentMessageEventFrom {
  PRIVATE_MESSAGE, GROUP_MESSAGE, GUILD_MESSAGE, PRIVATE_GUILD_MESSAGE
}

abstract class AbstractEvent

abstract class TencentEvent : AbstractEvent() {
  abstract val bot: TencentBot
  val logger get() = bot.logger
}

internal data class TencentBotWebsocketHandshakeSuccessEvent(override val bot: TencentBot) : TencentEvent()

abstract class TencentMessageEvent(
  override val bot: TencentBot,
  val message: MessageChain
) : TencentEvent() {
  abstract val subject: Contact
  abstract val sender: User
}

//abstract class TencentMessageEvent1(
//  val messageId: String
//) : TencentEvent() {
//  suspend fun sendMessage(message: String) = sendMessage(message.toPlainText())
//  suspend fun sendMessage(message: Message) = sendMessage(TencentMessageBuilder(this).append(message).build())
//  private suspend fun sendMessage(
//    message: TencentMessage
//  ): Result<Unit> {
//    val bodyString = bot.json.encodeToString(message)
//    return when(this) {
//      is TencentGuildMessageEvent -> {
//        postOpenapi(
//          TencentEndpoint.PostGuildMessage,
//          bodyString,
//          mapOf("channel_id" to this.sourceMessage.channelId)
//        )
//      }
//      else -> {
//        Result.failure(Exception("no implement"))
//      }
//    }
//  }
//  private suspend fun postOpenapi(
//    endpoint: TencentEndpoint,
//    content: String,
//    urlPlaceHolder: Map<String, String> = mapOf()
//  ): Result<Unit> {
//    return bot.callOpenapi(endpoint, urlPlaceHolder) {
//      method = HttpMethod.Post
//      contentType(ContentType.Application.Json)
//      setBody(content)
//    }
//  }
//}

// 频道消息事件
class TencentGuildMessageEvent internal constructor(
  message: MessageChain,
  override val sender: GuildMember,
) : TencentMessageEvent(sender.bot, message) {
  override val subject get() = sender.channel
}

// 频道私聊消息事件
class TencentGuildPrivateMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: GuildUser,
  internal val sourceMessage: TencentGuildMessage
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender.guild
}

class TencentSingleMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: SingleUser,
  internal val sourceMessage: TencentGuildMessage
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender
}

class TencentGroupMessageEvent internal constructor(
  bot: TencentBot,
  message: MessageChain,
  override val sender: GroupMember,
  internal val sourceMessage: TencentGuildMessage
) : TencentMessageEvent(bot, message) {
  override val subject get() = sender.group
}

internal data class TencentBotWebsocketAuthSuccessEvent(
  override val bot: TencentBot,
  val payload: TencentWebsocketIdentifyResp
) : TencentEvent()

data class TencentBotOnlineEvent(
  override val bot: TencentBot,
) : TencentEvent()
