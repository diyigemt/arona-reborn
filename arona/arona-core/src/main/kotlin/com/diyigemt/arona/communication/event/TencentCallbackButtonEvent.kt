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
   * 互动事件 ID (对应 INTERACTION_CREATE 事件的 `d.id`).
   * 仅用于 [accept]/[reject] 调 PUT /interactions/{interaction_id} 的 interaction_id.
   *
   * 注意: 不要拿它当被动回复的 event_id —— 被动回复请用 [internalId], 原因见该字段说明.
   */
  val id: String,
  /**
   * webhook 信封顶层 id (与 [id] 是不同的 JSON 字段), 被动回复消息时的 event_id 取此值.
   *
   * 文档虽把 `d.id` 描述成"用于被动消息发送和互动回调", 但线上实测: 被动回复带 `d.id` 会被
   * PostGroupMessage 以 `400 Bad Request 请求参数event_id无效` 拒绝, 只有信封顶层 id 才被接受.
   * 故此处以实测行为为准, 不按文档字面收敛这两个 id.
   */
  val internalId: String,
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
  // 被动回复的 event_id 取 webhook 信封顶层 id ([internalId]), 而非 d.id ([id]).
  // 曾按文档字面把两者收敛到 d.id, 线上被 PostGroupMessage 判为 event_id 无效(400), 已回滚.
  // PUT 回执的 interaction_id 仍取 [id](d.id), 两条链路各用各的 id, 不要再合并.
  override val eventId
    get() = internalId

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
