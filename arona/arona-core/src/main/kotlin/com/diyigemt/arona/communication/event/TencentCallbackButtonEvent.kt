package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.User
import com.diyigemt.arona.communication.message.TencentWebsocketInteractionNotifyReq
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = TencentCallbackButtonEventResult.Companion::class)
enum class TencentCallbackButtonEventResult(val code: Int) {
  Success(0),
  Failed(1),
  Busy(2),
  Duplicate(3),
  PermissionDeny(4),
  AdminOnly(5);
  companion object : KSerializer<TencentCallbackButtonEventResult> {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: Success
    override val descriptor = PrimitiveSerialDescriptor("TencentCallbackButtonEventResult", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) = fromValue(decoder.decodeInt())

    override fun serialize(encoder: Encoder, value: TencentCallbackButtonEventResult) = encoder.encodeInt(value.code)
  }
}

data class TencentCallbackButtonEvent(
  val id: String, // 事件id
  val internalId: String, // websocket消息id 用于消息回复?
  val appId: String, // botAppId
  val buttonId: String,
  val buttonData: String,
  val type: TencentWebsocketCallbackButtonType,
  val chatType: TencentWebsocketCallbackButtonChatType,
  val contact: Contact,
  val user: User,
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId
    get() = internalId

  suspend fun accept() {
    reject(TencentCallbackButtonEventResult.Success)
  }

  suspend fun reject(reason: TencentCallbackButtonEventResult = TencentCallbackButtonEventResult.Failed) {
    bot.callOpenapi(
      TencentEndpoint.Interactions,
      urlPlaceHolder = mapOf("interaction_id" to id)
    ) {
      method = HttpMethod.Put
      setBody(bot.json.encodeToString(TencentWebsocketInteractionNotifyReq(reason)))
    }
  }
}

data class TencentCallbackButtonFilter(
  val buttonId: String,
  val buttonData: String
)
