package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.at
import com.diyigemt.arona.communication.message.code
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentCharset
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.security.SecureRandom

internal const val WEBHOOK_PATH = "/webhook"
internal val ALIAS_PATTERN = Regex("[A-Za-z0-9_-]{1,32}")
private const val RATE_LIMIT_KEY = "webhook"
private const val TRUNCATED_MARK = "…(已截断)"

/** 一个推送目标的运行期形态, 由配置校验通过后构建. */
internal class ResolvedTarget(
  val groupId: String,
  val userId: String,
  authorization: String,
  /** null 表示不限来源, 来源未知也放行. */
  private val allowedIps: List<IpRange>?,
  val limiter: IpRateLimiter,
) {
  val credentialDigest: ByteArray = sha256(authorization)

  fun allows(ip: ByteArray?): Boolean = allowedIps == null || (ip != null && allowedIps.any { ip in it })
}

internal enum class BodyFormat { MARKDOWN, JSON, PLAIN }

private class AuditRecord(val alias: String) {
  var ip = "未知"
  var bytes = 0
  var result = "异常"

  override fun toString() = "webhook alias=$alias ip=$ip bytes=$bytes result=$result"
}

/**
 * webhook 入口: `POST /webhook/{alias}`. [send] 同步等待平台回执, 调用方因此能从状态码判断是否需要重试:
 * 200 = 已送达平台, 502 = 未确认送达.
 *
 * 顺序: 鉴权 401 → 来源 IP 403 → 读请求体 413/400 → 限流 429 → 发送. 限流放在最后, 坏请求不消耗发送额度.
 * 每个请求结束时向 [audit] 写一条记录.
 */
internal fun Application.webhookModule(
  targets: Map<String, ResolvedTarget>,
  maxBodyBytes: Long,
  maxContentChars: Int,
  globalLimiter: IpRateLimiter,
  resolveClientIp: (ApplicationRequest) -> ByteArray?,
  audit: (String) -> Unit = {},
  send: suspend (groupId: String, message: TencentCustomMarkdown) -> Boolean,
) {
  // 别名不存在时也比较一次, 和凭证错误走同样的路径, 不从耗时上暴露哪些别名存在.
  val unknownDigest = ByteArray(32).also(SecureRandom()::nextBytes)

  suspend fun handle(call: ApplicationCall, target: ResolvedTarget?, record: AuditRecord): HttpStatusCode {
    val ip = resolveClientIp(call.request)
    record.ip = ip?.let(::formatIp) ?: "未知"
    // 头重复时拒绝, 不猜该用哪一个. 比较固定长度的摘要, 比较这一步的耗时与凭证长度和别名是否存在无关.
    val given = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()
    val matched = MessageDigest.isEqual(target?.credentialDigest ?: unknownDigest, sha256(given.orEmpty()))
    if (target == null || given == null || !matched) return HttpStatusCode.Unauthorized
    if (!target.allows(ip)) return HttpStatusCode.Forbidden
    // 不信任 Content-Length: chunked 请求没有它. 只多读 1 字节用于判断超限, 不把整个 body 读进内存.
    val bytes = call.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
    record.bytes = bytes.size
    // 连接中断时引擎按正常结束关闭 body 通道, readRemaining 会返回已读到的部分, 半截内容不能当完整消息转发.
    currentCoroutineContext().ensureActive()
    if (bytes.size > maxBodyBytes) return HttpStatusCode.PayloadTooLarge
    // Content-Type 写坏不该让一条通知丢失: 类型解析失败按 Markdown, 字符集无法识别按 UTF-8.
    val contentType = runCatching { call.request.contentType() }.getOrNull()
    val charset = runCatching { call.request.contentCharset() }.getOrNull() ?: Charsets.UTF_8
    val body = String(bytes, charset)
    if (body.isBlank()) return HttpStatusCode.BadRequest
    // 先扣目标桶: 目标自身超额时不占用共享额度. 反过来全局桶拒绝时目标桶已扣掉一枚, 可以接受.
    if (!target.limiter.tryConsume(RATE_LIMIT_KEY) || !globalLimiter.tryConsume(RATE_LIMIT_KEY)) {
      return HttpStatusCode.TooManyRequests
    }
    val message = buildMessage(target.userId, body, bodyFormat(contentType), maxContentChars)
    return if (send(target.groupId, message)) HttpStatusCode.OK else HttpStatusCode.BadGateway
  }

  // 连接断开时取消本次调用. 这是唯一能发现 chunked 请求体被截断的信号, 它没有 Content-Length 可比对.
  install(HttpRequestLifecycle) {
    cancelCallOnClose = true
  }
  routing {
    post("$WEBHOOK_PATH/{alias}") {
      // 只取路径参数: call.parameters 里同名查询参数优先, ?alias=xxx 就能让实际目标和路径不一致.
      val alias = call.pathParameters["alias"].orEmpty()
      val record = AuditRecord(alias.takeIf { ALIAS_PATTERN.matches(it) } ?: "<非法>")
      try {
        val status = handle(call, targets[alias], record)
        record.result = status.value.toString()
        call.respond(status)
      } catch (e: CancellationException) {
        record.result = "连接断开或读取超时"
        throw e
      } finally {
        audit(record.toString())
      }
    }
  }
}

/** 按声明的媒体类型选择渲染方式, 不看内容. 缺省和其他类型 (如 curl 默认的表单类型) 按 Markdown. */
internal fun bodyFormat(contentType: ContentType?): BodyFormat = when {
  contentType == null -> BodyFormat.MARKDOWN
  contentType.match(ContentType.Application.Json) -> BodyFormat.JSON
  contentType.contentSubtype.endsWith("+json", ignoreCase = true) -> BodyFormat.JSON
  contentType.match(ContentType.Text.Plain) -> BodyFormat.PLAIN
  else -> BodyFormat.MARKDOWN
}

/**
 * 拼出实际发送的消息. 统一走 Markdown: 纯文本里的 `<qqbot-at-user>` 平台不解析 (真机确认), @ 只在 Markdown 里生效.
 * - MARKDOWN: 不转义, 正文里的 @ 标签同样生效.
 * - JSON: 放进 json 代码块, 免得字段名里的 `_` 之类被渲染吃掉.
 * - PLAIN: 放进无语言代码块按字面显示. 不用反斜杠转义, 因为 QQ Markdown 是否支持它不确定.
 */
internal fun buildMessage(userId: String, body: String, format: BodyFormat, maxChars: Int): TencentCustomMarkdown =
  tencentCustomMarkdown {
    if (userId.isNotBlank()) at(userId)
    when (format) {
      BodyFormat.MARKDOWN -> +truncate(body, maxChars)
      BodyFormat.JSON -> {
        // 反引号在合法 JSON 里只会出现在字符串中, 换成 unicode 转义语义不变, 且不会提前闭合代码块.
        val json = body.trim().replace("```", "\\u0060\\u0060\\u0060")
        code("json") { +truncate(json, maxChars) }
      }
      BodyFormat.PLAIN -> {
        val text = truncate(body.trim('\r', '\n'), maxChars)
        // 围栏比正文里最长的连续反引号多一个, 正文里的反引号就不会闭合代码块, 原样保留.
        val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
        +"$fence\n$text\n$fence"
      }
    }
  }

private fun longestBacktickRun(text: String): Int {
  var longest = 0
  var current = 0
  for (c in text) {
    current = if (c == '`') current + 1 else 0
    longest = maxOf(longest, current)
  }
  return longest
}

private fun truncate(text: String, maxChars: Int): String {
  if (text.length <= maxChars) return text
  // 不把 emoji 的代理对切成两半: 落单的高位代理编码成 JSON 后是非法字符.
  val end = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
  return text.substring(0, end) + TRUNCATED_MARK
}

internal fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
