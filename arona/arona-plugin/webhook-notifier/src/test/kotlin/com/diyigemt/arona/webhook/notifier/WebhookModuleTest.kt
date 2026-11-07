package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

private const val TOKEN = "Bearer secret"

class WebhookModuleTest {
  private val sent = mutableListOf<String>()

  private fun webhookTest(
    capacity: Long = 10,
    maxBodyBytes: Long = 1024,
    sendResult: Boolean = true,
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      webhookModule(TOKEN, maxBodyBytes, IpRateLimiter(capacity, capacity, 60)) {
        sent += it
        sendResult
      }
    }
    block()
  }

  private suspend fun ApplicationTestBuilder.hook(body: String, auth: String? = TOKEN): HttpResponse =
    client.post(WEBHOOK_PATH) {
      auth?.let { header(HttpHeaders.Authorization, it) }
      setBody(body)
    }

  @Test
  fun `forwards the body verbatim`() = webhookTest {
    assertEquals(HttpStatusCode.OK, hook(" {\"a\": \"中文\"}\n").status)
    assertEquals(listOf(" {\"a\": \"中文\"}\n"), sent)
  }

  @Test
  fun `decodes by declared charset and falls back to utf8`() = webhookTest {
    suspend fun post(bytes: ByteArray, contentType: String?) = client.post(WEBHOOK_PATH) {
      header(HttpHeaders.Authorization, TOKEN)
      contentType?.let { header(HttpHeaders.ContentType, it) }
      setBody(bytes)
    }
    post("中文".toByteArray(charset("GBK")), "text/plain; charset=GBK")
    post("中文".toByteArray(), null)
    post("中文".toByteArray(), "text/plain; charset=no-such-charset")
    assertEquals(List(3) { "中文" }, sent)
  }

  @Test
  fun `rejects missing and wrong authorization without sending`() = webhookTest {
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = null).status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = "Bearer wrong").status)
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = "secret").status)
    assertEquals(emptyList(), sent)
  }

  @Test
  fun `body limit is inclusive`() = webhookTest(maxBodyBytes = 4) {
    assertEquals(HttpStatusCode.OK, hook("1234").status)
    assertEquals(HttpStatusCode.PayloadTooLarge, hook("12345").status)
    assertEquals(listOf("1234"), sent)
  }

  @Test
  fun `blank body is rejected`() = webhookTest {
    assertEquals(HttpStatusCode.BadRequest, hook(" \n ").status)
    assertEquals(emptyList(), sent)
  }

  @Test
  fun `only valid requests consume the rate limit`() = webhookTest(capacity = 1) {
    assertEquals(HttpStatusCode.Unauthorized, hook("x", auth = "bad").status)
    assertEquals(HttpStatusCode.BadRequest, hook(" ").status)
    assertEquals(HttpStatusCode.OK, hook("first").status)
    assertEquals(HttpStatusCode.TooManyRequests, hook("second").status)
    assertEquals(listOf("first"), sent)
  }

  @Test
  fun `send failure maps to bad gateway`() = webhookTest(sendResult = false) {
    assertEquals(HttpStatusCode.BadGateway, hook("x").status)
  }

  @Test
  fun `prefixes the at tag only when a user is configured`() {
    assertEquals("<qqbot-at-user id=\"u1\" />\nhi", buildMessage("u1", "hi", 10).content)
    assertEquals("hi", buildMessage("", "hi", 10).content)
  }

  @Test
  fun `json objects and arrays go into a code block after the at`() {
    assertEquals("<qqbot-at-user id=\"u1\" />\n``` json\n{\"a_b\": 1}\n```", buildMessage("u1", " {\"a_b\": 1}\n", 100).content)
    assertEquals("``` json\n[1, 2]\n```", buildMessage("", "[1, 2]", 100).content)
  }

  @Test
  fun `scalars and invalid json stay as plain markdown`() {
    listOf("123", "\"text\"", "true", "{not json", "[1,", "磁盘告警").forEach {
      assertEquals(it, buildMessage("", it, 100).content)
    }
  }

  @Test
  fun `backticks in json cannot close the code block`() {
    val message = buildMessage("", "{\"a\": \"```\\n# x\"}", 100).content
    assertEquals("``` json\n{\"a\": \"\\u0060\\u0060\\u0060\\n# x\"}\n```", message)
  }

  @Test
  fun `truncated json keeps the code block closed`() {
    assertEquals("``` json\n{\"a\"…(已截断)\n```", buildMessage("", "{\"a\": 12345}", 4).content)
  }

  @Test
  fun `truncation marks the cut and keeps surrogate pairs whole`() {
    assertEquals("12…(已截断)", buildMessage("", "123", 2).content)
    assertEquals("123", buildMessage("", "123", 3).content)
    assertEquals("a…(已截断)", buildMessage("", "a😀b", 2).content)
  }
}
