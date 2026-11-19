package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OPS_TOKEN = "Bearer ops-secret"
private const val CI_TOKEN = "Bearer ci-secret"
private const val TEST_IP_HEADER = "X-Test-Ip"
private const val FENCE = "```"

class WebhookModuleTest {
  private val sent = mutableListOf<Pair<String, String>>()

  private fun webhookTest(
    targetCapacity: Long = 10,
    globalCapacity: Long = 10,
    maxBodyBytes: Long = 1024,
    sendResult: Boolean = true,
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    val targets = mapOf(
      "ops" to ResolvedTarget("g-ops", "", OPS_TOKEN, null, IpRateLimiter(targetCapacity, targetCapacity, 60)),
      "ci" to ResolvedTarget("g-ci", "u1", CI_TOKEN, listOf(IpRange.parse("10.0.0.0/8")!!), IpRateLimiter(10, 10, 60)),
    )
    application {
      webhookModule(
        targets = targets,
        maxBodyBytes = maxBodyBytes,
        maxContentChars = 100,
        globalLimiter = IpRateLimiter(globalCapacity, globalCapacity, 60),
        // 测试客户端没有真实来源地址, 由请求头模拟解析结果.
        resolveClientIp = { request -> request.headers[TEST_IP_HEADER]?.let(::parseClientIp) },
      ) { groupId, message ->
        sent += groupId to message.content
        sendResult
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.hook(
    body: String,
    alias: String = "ops",
    auth: String? = OPS_TOKEN,
    ip: String? = null,
    extra: HttpRequestBuilder.() -> Unit = {},
  ): HttpResponse = client.post("$WEBHOOK_PATH/$alias") {
    auth?.let { header(HttpHeaders.Authorization, it) }
    ip?.let { header(TEST_IP_HEADER, it) }
    // 用字节发送: 字符串正文会被客户端默认标成 text/plain, 改变渲染方式.
    setBody(body.toByteArray())
    extra()
  }

  @Test
  fun `each alias forwards to its own group`() = webhookTest {
    assertEquals(HttpStatusCode.OK, hook("a").status)
    assertEquals(HttpStatusCode.OK, hook("b", alias = "ci", auth = CI_TOKEN, ip = "10.1.2.3").status)
    assertEquals(listOf("g-ops" to "a", "g-ci" to "<qqbot-at-user id=\"u1\" />\nb"), sent)
  }

  @Test
  fun `credentials are bound to their alias`() = webhookTest {
    assertEquals(HttpStatusCode.Unauthorized, hook("x", alias = "ci", auth = OPS_TOKEN, ip = "10.1.2.3").status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x", alias = "ops", auth = CI_TOKEN).status)
    assertEquals(emptyList(), sent)
  }

  @Test
  fun `unknown alias, bad or repeated authorization all look the same`() = webhookTest {
    assertEquals(HttpStatusCode.Unauthorized, hook("x", alias = "nope").status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = null).status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = "Bearer wrong").status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x") { header(HttpHeaders.Authorization, OPS_TOKEN) }.status)
    assertEquals(emptyList(), sent)
  }

  @Test
  fun `query parameters cannot redirect the target`() = webhookTest {
    val response = client.post("$WEBHOOK_PATH/ops?alias=ci") {
      header(HttpHeaders.Authorization, CI_TOKEN)
      header(TEST_IP_HEADER, "10.0.0.1")
      setBody("x".toByteArray())
    }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals(HttpStatusCode.OK, hook("y", alias = "ops?alias=ci").status)
    assertEquals(listOf("g-ops" to "y"), sent)
  }

  @Test
  fun `the old fixed path is gone`() = webhookTest {
    val response = client.post(WEBHOOK_PATH) {
      header(HttpHeaders.Authorization, OPS_TOKEN)
      setBody("x")
    }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test
  fun `ip allowlist rejects outside and unknown sources`() = webhookTest {
    assertEquals(HttpStatusCode.Forbidden, hook("x", alias = "ci", auth = CI_TOKEN, ip = "11.0.0.1").status)
    assertEquals(HttpStatusCode.Forbidden, hook("x", alias = "ci", auth = CI_TOKEN, ip = null).status)
    assertEquals(HttpStatusCode.OK, hook("ok", alias = "ci", auth = CI_TOKEN, ip = "::ffff:10.0.0.1").status)
    // 不限来源的目标连来源未知也放行.
    assertEquals(HttpStatusCode.OK, hook("ok", ip = null).status)
    assertEquals(2, sent.size)
  }

  @Test
  fun `decodes by declared charset and falls back to utf8`() = webhookTest {
    suspend fun post(bytes: ByteArray, contentType: String?) = client.post("$WEBHOOK_PATH/ops") {
      header(HttpHeaders.Authorization, OPS_TOKEN)
      contentType?.let { header(HttpHeaders.ContentType, it) }
      setBody(bytes)
    }
    post("中文".toByteArray(charset("GBK")), "text/markdown; charset=GBK")
    post("中文".toByteArray(), null)
    post("中文".toByteArray(), "text/markdown; charset=no-such-charset")
    assertEquals(List(3) { "g-ops" to "中文" }, sent)
  }

  @Test
  fun `body limit is inclusive`() = webhookTest(maxBodyBytes = 4) {
    assertEquals(HttpStatusCode.OK, hook("1234").status)
    assertEquals(HttpStatusCode.PayloadTooLarge, hook("12345").status)
    assertEquals(listOf("g-ops" to "1234"), sent)
  }

  @Test
  fun `blank body is rejected`() = webhookTest {
    assertEquals(HttpStatusCode.BadRequest, hook(" \n ").status)
    assertEquals(emptyList(), sent)
  }

  @Test
  // 全局容量留出余量, 这样第二个目标能成功只取决于目标桶各自独立.
  fun `only valid requests consume the target limit`() = webhookTest(targetCapacity = 1, globalCapacity = 2) {
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = "bad").status)
    assertEquals(HttpStatusCode.BadRequest, hook(" ").status)
    assertEquals(HttpStatusCode.OK, hook("first").status)
    assertEquals(HttpStatusCode.TooManyRequests, hook("second").status)
    // 一个目标用完额度不影响其他目标.
    assertEquals(HttpStatusCode.OK, hook("other", alias = "ci", auth = CI_TOKEN, ip = "10.0.0.1").status)
    assertEquals(2, sent.size)
  }

  @Test
  fun `global limit caps all targets together`() = webhookTest(globalCapacity = 1) {
    assertEquals(HttpStatusCode.OK, hook("first").status)
    assertEquals(HttpStatusCode.TooManyRequests, hook("second", alias = "ci", auth = CI_TOKEN, ip = "10.0.0.1").status)
    assertEquals(1, sent.size)
  }

  @Test
  fun `send failure maps to bad gateway`() = webhookTest(sendResult = false) {
    assertEquals(HttpStatusCode.BadGateway, hook("x").status)
  }

  @Test
  fun `content type decides how the body is rendered`() = webhookTest {
    suspend fun post(body: String, contentType: String) = hook(body) { header(HttpHeaders.ContentType, contentType) }
    post("**粗体**", "text/markdown")
    post("{\"a_b\": 1}", "application/json")
    post("[1]", "application/vnd.api+json")
    post("not json", "application/json")
    post("a_b *c*", "text/plain")
    post("{\"a_b\": 1}", "application/x-www-form-urlencoded")
    assertEquals(
      listOf(
        "**粗体**",
        "$FENCE json\n{\"a_b\": 1}\n$FENCE",
        "$FENCE json\n[1]\n$FENCE",
        "$FENCE json\nnot json\n$FENCE",
        "$FENCE\na_b *c*\n$FENCE",
        "{\"a_b\": 1}",
      ),
      sent.map { it.second },
    )
  }

  @Test
  fun `json content type is recognized case insensitively`() {
    assertEquals(BodyFormat.JSON, bodyFormat(ContentType.parse("Application/JSON; charset=utf-8")))
    assertEquals(BodyFormat.PLAIN, bodyFormat(ContentType.parse("TEXT/PLAIN")))
  }

  @Test
  fun `at tags in markdown bodies are kept`() {
    val body = "<qqbot-at-user id=\"u2\" /> 看一下"
    assertEquals(body, buildMessage("", body, BodyFormat.MARKDOWN, 100).content)
  }

  @Test
  fun `backticks in json cannot close the code block`() {
    val message = buildMessage("", "{\"a\": \"$FENCE\\n# x\"}", BodyFormat.JSON, 100).content
    assertEquals("$FENCE json\n{\"a\": \"\\u0060\\u0060\\u0060\\n# x\"}\n$FENCE", message)
  }

  @Test
  fun `plain text fence outgrows the backticks it contains`() {
    val message = buildMessage("", "\nrun $FENCE x $FENCE\n", BodyFormat.PLAIN, 100).content
    assertEquals("$FENCE`\nrun $FENCE x $FENCE\n$FENCE`", message)
  }

  @Test
  fun `truncated json keeps the code block closed`() {
    assertEquals("$FENCE json\n{\"a\"…(已截断)\n$FENCE", buildMessage("", "{\"a\": 12345}", BodyFormat.JSON, 4).content)
  }

  @Test
  fun `truncation marks the cut and keeps surrogate pairs whole`() {
    assertEquals("12…(已截断)", buildMessage("", "123", BodyFormat.MARKDOWN, 2).content)
    assertEquals("123", buildMessage("", "123", BodyFormat.MARKDOWN, 3).content)
    assertEquals("a…(已截断)", buildMessage("", "a😀b", BodyFormat.MARKDOWN, 2).content)
  }
}
