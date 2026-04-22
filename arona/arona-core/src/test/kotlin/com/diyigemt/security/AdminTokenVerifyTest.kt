package com.diyigemt.security

import com.diyigemt.arona.webui.endpoints.verifyAdminToken
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminTokenVerifyTest {
  private val expected = "s3cret-admin-token"

  @Test
  fun `null 与空字符串都应拒绝`() {
    assertFalse(verifyAdminToken(null, expected))
    assertFalse(verifyAdminToken("", expected))
  }

  @Test
  fun `expected 为空时禁止任何 token 通过`() {
    assertFalse(verifyAdminToken("", ""))
    assertFalse(verifyAdminToken(expected, ""))
    assertFalse(verifyAdminToken(expected, "   "))
  }

  @Test
  fun `错误 token 拒绝`() {
    assertFalse(verifyAdminToken("wrong", expected))
  }

  @Test
  fun `正确 token 接受`() {
    assertTrue(verifyAdminToken(expected, expected))
  }

  @Test
  fun `长度不同的 token 仍通过同一比较入口被拒绝`() {
    // 不能短路依赖长度差, 但结果必须是拒绝.
    assertFalse(verifyAdminToken("short", expected))
    assertFalse(verifyAdminToken(expected + "-extra", expected))
  }

  @Test
  fun `Unicode token 比较应以 UTF-8 字节为准`() {
    val secret = "管理员密钥"
    assertTrue(verifyAdminToken(secret, secret))
    assertFalse(verifyAdminToken("管理员密钥X", secret))
  }
}
