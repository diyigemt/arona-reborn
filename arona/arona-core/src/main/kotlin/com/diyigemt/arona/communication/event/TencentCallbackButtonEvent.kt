package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.User

enum class TencentCallbackButtonEventResp(val code: Int) {
  Success(0),
  Failed(1),
  Busy(2),
  Duplicate(3),
  PermissionDeny(4),
  AdminOnly(5),
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
  var result: TencentCallbackButtonEventResp = TencentCallbackButtonEventResp.Success,
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  override val eventId
    get() = id
}

data class TencentCallbackButtonFilter(
  val buttonId: String,
  val buttonData: String
)
