package com.diyigemt.arona.webhook.notifier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetsValidationTest {
  private fun target(
    authorization: String = "Bearer a",
    allowedIps: List<String> = listOf(ANY_IP),
  ) = WebhookTarget(groupId = "g", authorization = authorization, allowedIps = allowedIps)

  private fun problems(vararg targets: Pair<String, WebhookTarget>) = targetsProblems(targets.toMap())

  @Test
  fun `a well formed target passes`() {
    assertEquals(emptyList(), problems("ops" to target(allowedIps = listOf("10.0.0.0/8", "::1"))))
  }

  @Test
  fun `a misspelled allowedIps key fails closed`() {
    // 宽松加载下拼错的键会被忽略, allowedIps 落到默认的空列表.
    assertEquals(1, problems("ops" to target(allowedIps = emptyList())).size)
  }

  @Test
  fun `wildcard cannot be mixed with addresses`() {
    assertEquals(1, problems("ops" to target(allowedIps = listOf(ANY_IP, "10.0.0.0/8"))).size)
  }

  @Test
  fun `unparseable addresses are reported`() {
    assertEquals(1, problems("ops" to target(allowedIps = listOf("example.com"))).size)
  }

  @Test
  fun `the same token under another scheme is still shared`() {
    val found = problems("a" to target("Bearer t"), "b" to target("Token t"))
    assertTrue(found.single().contains("a, b"), found.toString())
  }

  @Test
  fun `authorization must have exactly one separating space`() {
    listOf("Bearer  t", " Bearer t", "Bearer t ", "Bearer 令牌", "Bearer t x").forEach {
      assertEquals(1, problems("ops" to target(it)).size, it)
    }
    assertEquals(emptyList(), problems("ops" to target("bare-token")))
  }

  @Test
  fun `aliases are restricted`() {
    assertEquals(1, problems("a/b" to target()).size)
    assertEquals(1, problems("x".repeat(33) to target()).size)
  }
}
