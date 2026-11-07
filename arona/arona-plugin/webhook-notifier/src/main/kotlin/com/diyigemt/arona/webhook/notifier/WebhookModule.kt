package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.at
import com.diyigemt.arona.communication.message.code
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.contentCharset
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

internal const val WEBHOOK_PATH = "/webhook"
private const val RATE_LIMIT_KEY = "webhook"
private const val TRUNCATED_MARK = "…(已截断)"

/**
 * webhook 入口. [send] 同步等待平台回执, 调用方因此能从状态码判断是否需要重试: 200 = 已送达平台, 502 = 未确认送达.
 *
 * 限流放在鉴权和校验之后, 坏请求不消耗发送额度.
 */
internal fun Application.webhookModule(
  authorization: String,
  maxBodyBytes: Long,
  limiter: IpRateLimiter,
  send: suspend (String) -> Boolean,
) {
  val expected = authorization.toByteArray()
  // 连接断开时取消本次调用. 这是唯一能发现 chunked 请求体被截断的信号, 它没有 Content-Length 可比对.
  install(HttpRequestLifecycle) {
    cancelCallOnClose = true
  }
  routing {
    post(WEBHOOK_PATH) {
      val given = call.request.headers[HttpHeaders.Authorization].orEmpty().toByteArray()
      if (!MessageDigest.isEqual(expected, given)) {
        return@post call.respond(HttpStatusCode.Unauthorized)
      }
      // 不信任 Content-Length: chunked 请求没有它. 只多读 1 字节用于判断超限, 不把整个 body 读进内存.
      val bytes = call.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
      // 连接中断时引擎按正常结束关闭 body 通道, readRemaining 会返回已读到的部分, 半截内容不能当完整消息转发.
      currentCoroutineContext().ensureActive()
      if (bytes.size > maxBodyBytes) {
        return@post call.respond(HttpStatusCode.PayloadTooLarge)
      }
      // Content-Type 写坏不该让一条通知丢失, 按 UTF-8 尽力解码.
      val charset = runCatching { call.request.contentCharset() }.getOrNull() ?: Charsets.UTF_8
      val body = String(bytes, charset)
      if (body.isBlank()) {
        return@post call.respond(HttpStatusCode.BadRequest)
      }
      if (!limiter.tryConsume(RATE_LIMIT_KEY)) {
        return@post call.respond(HttpStatusCode.TooManyRequests)
      }
      call.respond(if (send(body)) HttpStatusCode.OK else HttpStatusCode.BadGateway)
    }
  }
}

/**
 * 拼出实际发送的消息. 统一走 Markdown: 纯文本里的 `<qqbot-at-user>` 平台不解析 (真机确认), @ 只在 Markdown 里生效.
 * JSON 放进代码块, 免得字段名里的 `_` 之类被渲染吃掉. 其余正文按 Markdown 渲染且不转义:
 * QQ Markdown 是否支持反斜杠转义不确定, 转义可能显示出多余的 `\`.
 */
internal fun buildMessage(userId: String, body: String, maxChars: Int): TencentCustomMarkdown =
  tencentCustomMarkdown {
    if (userId.isNotBlank()) at(userId)
    if (isJsonDocument(body)) {
      // 反引号在合法 JSON 里只会出现在字符串中, 换成 \u0060 语义不变, 且不会提前闭合代码块.
      val json = body.trim().replace("```", "\\u0060\\u0060\\u0060")
      code("json") { +truncate(json, maxChars) }
    } else {
      +truncate(body, maxChars)
    }
  }

/** 只认对象和数组: `123`、`"文本"` 这类标量也是合法 JSON, 但它们就是普通通知. */
private fun isJsonDocument(body: String): Boolean =
  runCatching { Json.parseToJsonElement(body) }.getOrNull().let { it is JsonObject || it is JsonArray }

private fun truncate(text: String, maxChars: Int): String {
  if (text.length <= maxChars) return text
  // 不把 emoji 的代理对切成两半: 落单的高位代理编码成 JSON 后是非法字符.
  val end = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
  return text.substring(0, end) + TRUNCATED_MARK
}
