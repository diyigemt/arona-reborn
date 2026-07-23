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
import kotlinx.coroutines.CancellationException
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
  /**
   * 互动事件 ID (对应 INTERACTION_CREATE 事件的 `d.id`). 文档定义此 id "用于被动消息发送和互动回调":
   * 既是 [accept]/[reject] 调 PUT /interactions/{interaction_id} 的 interaction_id,
   * 也是被动回复消息时的 event_id, 两处一律同源于本字段.
   */
  val id: String,
  val appId: String, // botAppId
  /**
   * 按钮 ID. 按钮点击(type=11)必有; 快捷菜单(type=12)不下发 button_id, 此时为空串, 应改用 [featureId] 识别.
   */
  val buttonId: String,
  val buttonData: String,
  /**
   * 快捷菜单(type=12)的 feature ID; 按钮点击(type=11)为 `null`.
   */
  val featureId: String?,
  val type: TencentWebsocketCallbackButtonType,
  val chatType: TencentWebsocketCallbackButtonChatType,
  val contact: Contact,
  val user: User,
  override val bot: TencentBot,
) : TencentBotEvent, TencentEvent() {
  // 被动回复(event_id)与 PUT 回执(interaction_id)按文档同源于事件的 id 字段(d.id), 故直接返回 [id].
  // 早期实现曾用 webhook 信封顶层 id (与 d.id 是不同 JSON 字段), 会让被动消息带上非法 event_id.
  override val eventId
    get() = id

  /**
   * 回执"接受"(code=0), 语义等价 [reject] 传 [TencentCallbackButtonEventResult.Success].
   * 返回底层 PUT 调用的 [Result]: 失败以 [Result.failure] 返回而非抛出, 失败日志见 [reject].
   */
  suspend fun accept(): Result<Unit> = reject(TencentCallbackButtonEventResult.Success)

  /**
   * 回执互动结果到 PUT /interactions/{interaction_id}.
   *
   * 返回底层 OpenAPI 调用的 [Result](与 [bot] 的 callOpenapi 一致): 普通 OpenAPI 失败以 [Result.failure]
   * 返回而非抛出, 并在此处补一条领域日志(interactionId/type/chatType/回执 ackCode + cause 简述);
   * 底层 callOpenapi 已记录通用/详细 OpenAPI 错误, 故此处只记简述避免重复刷栈.
   * 唯一例外是协程取消: [CancellationException] 会被继续抛出以维持协作式取消语义, 不当作普通回执失败吞掉
   * (底层 runCatching 会把取消也收进 Result.failure, 故必须在此显式重抛).
   * 调用方可安全忽略返回值(尽力而为回执), 也可据 [Result] 决定重试 / 告警 / 业务补偿.
   */
  suspend fun reject(
    reason: TencentCallbackButtonEventResult = TencentCallbackButtonEventResult.Failed,
  ): Result<Unit> = bot.callOpenapi(
    TencentEndpoint.Interactions,
    urlPlaceHolder = mapOf("interaction_id" to id)
  ) {
    method = HttpMethod.Put
    setBody(bot.json.encodeToString(TencentWebsocketInteractionNotifyReq(reason)))
  }.onFailure { cause ->
    // 底层 callOpenapi 用 runCatching 兜住整个 HTTP 调用, 会把 CancellationException 也转成 Result.failure;
    // 直接当普通失败记录+返回会让取消无法向上传播, 破坏结构化并发, 故先原样重抛.
    if (cause is CancellationException) throw cause
    logger.warn(
      "callback interaction ack failed: interactionId=$id, type=$type, chatType=$chatType, " +
          "ackCode=${reason.code}($reason), cause=${cause::class.simpleName}: ${cause.message}"
    )
  }
}

data class TencentCallbackButtonFilter(
  val buttonId: String,
  val buttonData: String,
  /** 快捷菜单(type=12)的 feature ID; 按钮点击(type=11)为 `null`. */
  val featureId: String? = null,
)
