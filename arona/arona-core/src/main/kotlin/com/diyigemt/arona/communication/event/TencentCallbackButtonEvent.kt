package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.User
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = TencentCallbackButtonEventResp.Companion::class)
enum class TencentCallbackButtonEventResp(val code: Int) {
  Success(0),
  Failed(1),
  Busy(2),
  Duplicate(3),
  PermissionDeny(4),
  AdminOnly(5);
  companion object : KSerializer<TencentCallbackButtonEventResp> {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: Success
    override val descriptor = PrimitiveSerialDescriptor("TencentCallbackButtonEventResp", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) = fromValue(decoder.decodeInt())

    override fun serialize(encoder: Encoder, value: TencentCallbackButtonEventResp) = encoder.encodeInt(value.code)
  }
}

data class TencentCallbackButtonEvent(
  val id: String, // 事件id
  val appId: String, // botAppId
  val buttonId: String,
  val buttonData: String,
  val type: TencentWebsocketCallbackButtonType,
  val chatType: TencentWebsocketCallbackButtonChatType,
  val contact: Contact,
  val user: User,
  var result: TencentCallbackButtonEventResp = TencentCallbackButtonEventResp.Failed,
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId
    get() = id
}

data class TencentCallbackButtonFilter(
  val buttonId: String,
  val buttonData: String
)
