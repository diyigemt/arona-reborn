package com.diyigemt.arona.chatbot

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 模型约定的输出结构: 要么说一句 [reply], 要么 [silent] 表示这轮不想说话. */
@Serializable
internal data class BotAction(
  val reply: String? = null,
  val silent: Boolean = false,
)

internal sealed interface LlmOutcome {
  data class Reply(val text: String) : LlmOutcome
  data class Noop(val reason: NoopReason, val detail: String? = null) : LlmOutcome
}

/**
 * 把模型输出文本解析成 [BotAction]. 容错: 去掉 ``` 围栏、取首个 `{` 到末个 `}`、lenient 解析.
 * 解析失败返回 null (调用方记 JSON_INVALID).
 */
internal fun parseBotAction(content: String): BotAction? {
  val start = content.indexOf('{')
  val end = content.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  return runCatching { LENIENT_JSON.decodeFromString(BotAction.serializer(), content.substring(start, end + 1)) }.getOrNull()
}

private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * DeepSeek (OpenAI 兼容) chat/completions 最小客户端. 只发文本, `response_format=json_object`.
 *
 * 官方承认 json_object 模式有概率返回空 content: 空则关掉 response_format 重试一次 (更短超时), 仍空记 JSON_EMPTY ——
 * 与 "模型明确表示沉默" (MODEL_SILENT) 严格区分, 否则必答消息会被静默吞掉.
 */
internal object DeepSeekClient {
  private val client = HttpClient(CIO) {
    install(HttpTimeout)
  }

  suspend fun chat(systemPrompt: String, userPrompt: String): LlmOutcome {
    var resp = request(systemPrompt, userPrompt, jsonMode = true, timeoutMillis = ChatbotSecrets.llmTimeoutMillis)
    if (resp is Response.Content && resp.text.isBlank()) {
      resp = request(systemPrompt, userPrompt, jsonMode = false, timeoutMillis = ChatbotSecrets.llmRetryTimeoutMillis)
    }
    return when (resp) {
      is Response.Error -> LlmOutcome.Noop(NoopReason.MODEL_ERROR, resp.detail)
      is Response.Content -> classify(resp.text)
    }
  }

  /** 模型 content → 结果 (纯函数). 空 content 是 JSON_EMPTY, 模型明确 silent 才是 MODEL_SILENT, 两者不能混. */
  internal fun classify(content: String): LlmOutcome {
    if (content.isBlank()) return LlmOutcome.Noop(NoopReason.JSON_EMPTY)
    val action = parseBotAction(content) ?: return LlmOutcome.Noop(NoopReason.JSON_INVALID, content.take(200))
    val reply = action.reply?.trim().orEmpty()
    if (action.silent || reply.isEmpty()) return LlmOutcome.Noop(NoopReason.MODEL_SILENT)
    return LlmOutcome.Reply(reply)
  }

  private sealed interface Response {
    data class Content(val text: String) : Response
    data class Error(val detail: String) : Response
  }

  private suspend fun request(system: String, user: String, jsonMode: Boolean, timeoutMillis: Long): Response {
    val body = buildJsonObject {
      put("model", ChatbotSecrets.chatModel)
      put("messages", buildJsonArray {
        add(buildJsonObject { put("role", "system"); put("content", system) })
        add(buildJsonObject { put("role", "user"); put("content", user) })
      })
      if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
    }
    val raw = runCatchingCancellable {
      client.post("${ChatbotSecrets.baseUrl.trimEnd('/')}/chat/completions") {
        bearerAuth(ChatbotSecrets.apiKey)
        contentType(ContentType.Application.Json)
        timeout { requestTimeoutMillis = timeoutMillis }
        setBody(body.toString())
      }.bodyAsText()
    }.getOrElse { return Response.Error("${it::class.simpleName}: ${it.message}") }
    return extractContent(raw)
  }

  /** OpenAI 形态: `choices[0].message.content`; 带 `error` 对象的响应按错误处理. */
  private fun extractContent(raw: String): Response = runCatching {
    val root = LENIENT_JSON.parseToJsonElement(raw).jsonObject
    root["error"]?.takeIf { it !is JsonNull }?.let { return Response.Error(it.toString().take(300)) }
    val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
      ?.get("message")?.jsonObject?.get("content")
    Response.Content((content as? JsonPrimitive)?.contentOrNull.orEmpty())
  }.getOrElse { Response.Error("unexpected response: ${raw.take(300)}") }
}
