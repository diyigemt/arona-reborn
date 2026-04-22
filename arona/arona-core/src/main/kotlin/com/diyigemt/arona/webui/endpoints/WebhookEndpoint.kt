package com.diyigemt.arona.webui.endpoints

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.TencentBotClient
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.utils.apiLogger
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.utils.unauthorized
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.util.hex
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

internal object TencentWebhookOperationTypeAsIntSerializer : KSerializer<TencentWebhookOperationType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentWebhookOperationType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentWebhookOperationType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentWebhookOperationType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentWebhookOperationTypeAsIntSerializer::class)
internal enum class TencentWebhookOperationType(val code: Int) {
  Dispatch(0),
  WebhookVerify(13);

  companion object {
    private val TypeMap = entries.associateBy { it.code }
    fun fromValue(code: Int) = TypeMap[code] ?: Dispatch
  }
}

internal typealias TencentWebhookEventType = TencentWebsocketEventType

@Serializable
internal data class TencentWebhookPayload0(
  @SerialName("op")
  val operation: TencentWebhookOperationType = TencentWebhookOperationType.Dispatch,
  @SerialName("t")
  val type: TencentWebhookEventType = TencentWebhookEventType.NULL
)

@Serializable
internal data class TencentWebhookPayload<T>(
  @SerialName("op")
  val operation: TencentWebhookOperationType = TencentWebhookOperationType.Dispatch,
  @SerialName("t")
  val type: TencentWebhookEventType = TencentWebhookEventType.NULL,
  @SerialName("d")
  val data: T,
)

@Serializable
internal data class TencentWebhookVerifyReq(
  @SerialName("plain_token")
  val plainToken: String,
  @SerialName("event_ts")
  val eventTs: String,
)

@Serializable
internal data class TencentWebhookVerifyResp(
  @SerialName("plain_token")
  val plainToken: String,
  @SerialName("signature")
  val signature: String,
)

internal const val MaxWebhookBodyBytes = 64L * 1024L

/**
 * 严格 hex 解析: 奇数长度或包含非十六进制字符返回 null. 空串返回空数组 (验签必失败).
 */
internal fun parseHexOrNull(hex: String): ByteArray? {
  if (hex.length % 2 != 0) return null
  val data = ByteArray(hex.length / 2)
  for (i in hex.indices step 2) {
    val high = Character.digit(hex[i], 16)
    val low = Character.digit(hex[i + 1], 16)
    if (high < 0 || low < 0) return null
    data[i / 2] = ((high shl 4) + low).toByte()
  }
  return data
}

@AronaBackendEndpoint("/webhook")
@Suppress("unused")
object WebhookEndpoint {
  private val json = Json {
    ignoreUnknownKeys = true
  }

  @OptIn(ExperimentalStdlibApi::class)
  @AronaBackendEndpointPost("")
  suspend fun PipelineContext<Unit, ApplicationCall>.webhook() {
    val sign = request.header("x-signature-ed25519") ?: return unauthorized()
    val ts = request.header("x-signature-timestamp") ?: return unauthorized()
    // 强制 Content-Length, 拒绝 chunked: 否则攻击者可绕过预检直接占用内存.
    val declared = request.contentLength() ?: return badRequest()
    if (declared <= 0L || declared > MaxWebhookBodyBytes) return badRequest()
    val signBytes = parseHexOrNull(sign) ?: return badRequest()
    val body = context.receiveText()
    if (body.toByteArray(Charsets.UTF_8).size > MaxWebhookBodyBytes) {
      return badRequest()
    }
    val verify = (BotManager.getBot() as TencentBotClient)
      .webHookVerify((ts + body).toByteArray(Charsets.UTF_8), signBytes)
    if (!verify) {
      apiLogger.warn("webhook signature verify failed.")
      return unauthorized()
    }
    val preData = json.decodeFromString<TencentWebhookPayload0>(body)
    if (preData.operation == TencentWebhookOperationType.WebhookVerify) {
      val data = json.decodeFromString<TencentWebhookPayload<TencentWebhookVerifyReq>>(body)
      return context.respond(
        TencentWebhookVerifyResp(
          data.data.plainToken,
          (BotManager.getBot() as TencentBotClient).webHookSign(
            (data.data.eventTs + data.data.plainToken).toByteArray(Charsets.UTF_8)
          ).toHexString()
        )
      )
    }
    (BotManager.getBot() as TencentBotClient).dispatchWebhookEvent(body)
    return success()
  }
}