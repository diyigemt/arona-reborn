package com.diyigemt.arona.webui.endpoints

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.TencentBotClient
import com.diyigemt.arona.communication.TencentWebsocketEventType
import com.diyigemt.arona.utils.apiLogger
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.success
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
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

@AronaBackendEndpoint("/webhook")
@Suppress("unused")
object WebhookEndpoint {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  @OptIn(ExperimentalStdlibApi::class)
  @AronaBackendEndpointPost("")
  suspend fun PipelineContext<Unit, ApplicationCall>.webhook() {
    val sign = request.header("x-signature-ed25519") ?: "" // 服务器传入的sign
    val ts = request.header("x-signature-timestamp") ?: "" // 服务器传入的ts
    val body = context.receiveText()
    apiLogger.info("recv webhook, data: $body")
    val verify = (BotManager.getBot() as TencentBotClient)
      .webHookVerify((ts + body).toByteArray(), sign.toByteArray())
    if (!verify) {
      apiLogger.error("webhook data verify failed.")
      return badRequest()
    }
    val preData = json.decodeFromString<TencentWebhookPayload0>(body)
    if (preData.operation == TencentWebhookOperationType.WebhookVerify) {
      val data = json.decodeFromString<TencentWebhookPayload<TencentWebhookVerifyReq>>(body)
      apiLogger.info("recv webhook verify, data: $data")
      return context.respond(
        TencentWebhookVerifyResp(
          data.data.plainToken,
          (BotManager.getBot() as TencentBotClient).webHookSign(
            (data.data.eventTs + data.data.plainToken).toByteArray()
          ).toHexString()
        )
      )
    }
    return success()
  }
}