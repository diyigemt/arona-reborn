package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.communication.event.TencentCallbackButtonEventResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Sprint 2.1 Part B: 彻底删除 WS op/session 层
//   - TencentWebsocketOperationHandler + 5 个 op handler (Hello/InvalidSession/Dispatch/HeartbeatAck/Reconnect)
//   - TencentWebsocketOperationManager (含 handleTencentOperation 反射分发)
//   - toWebSocketSession / TencentBotClientWebSocketSession
//   - TencentWebsocketOperationType enum + serializer
//   - WS 专属 DTO: HelloResp / IdentifyReq / IdentifyResp / IdentifyUserResp / ResumeReq / ResumeResp
// 保留项: webhook 仍需要 TencentWebsocketInteractionNotifyReq (callback ack), TencentWebsocketCallbackButtonResp
// 家族 (INTERACTION_CREATE handler), TencentWebsocketPayload/Payload0 (dispatch decode envelope).

@Serializable
internal data class TencentWebsocketInteractionNotifyReq(
  /**
   * 0: 成功
   *
   * 1: 操作失败
   *
   * 2: 操作频繁
   *
   * 3: 重复操作
   *
   * 4: 没有权限
   *
   * 5: 仅管理员操作
   */
  val code: TencentCallbackButtonEventResult,
)

@Serializable
data class TencentWebsocketCallbackButtonResp(
  val id: String,
  val type: TencentWebsocketCallbackButtonType,
  @SerialName("chat_type")
  val chatType: TencentWebsocketCallbackButtonChatType,
  val data: TencentWebsocketCallbackButtonDataResp,
  val timestamp: String,
  @SerialName("guild_id")
  val guildId: String? = null,
  @SerialName("channel_id")
  val channelId: String? = null,
  @SerialName("user_openid")
  val userOpenId: String? = null,
  @SerialName("group_openid")
  val groupOpenid: String? = null,
  @SerialName("group_member_openid")
  val groupMemberOpenid: String? = null,
  val version: Int = 1,
  @SerialName("application_id")
  val applicationId: String = ""
)

// 回调按钮消息体
@Serializable
data class TencentWebsocketCallbackButtonDataResp(
  val resolved: TencentWebsocketCallbackButtonDataResolvedResp,
  val type: TencentWebsocketCallbackButtonType,
)

@Serializable
data class TencentWebsocketCallbackButtonDataResolvedResp(
  @SerialName("button_data")
  val buttonData: String? = "",
  @SerialName("button_id")
  val buttonId: String,
  @SerialName("user_id")
  val userId: String? = null,
  @SerialName("feature_id")
  val featureId: String? = null,
  @SerialName("message_id")
  val messageId: String? = null,
)

// dispatch envelope: 旧 WS 协议字段 op/s 已被 Sprint 2.1 Part B 移除; webhook 自有 op 模型
// (TencentWebhookOperationType), dispatch 路径只读 id/t/d, 故此处只保留这三项. 入参 body 里多余的
// op/s 字段靠 Json.ignoreUnknownKeys=true 兼容.
@Serializable
internal data class TencentWebsocketPayload<T>(
  val id: String? = null,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL,
  @SerialName("d")
  val data: T,
)

@Serializable
internal data class TencentWebsocketPayload0(
  val id: String? = null,
  @SerialName("t")
  val type: TencentWebsocketEventType = TencentWebsocketEventType.NULL,
)
