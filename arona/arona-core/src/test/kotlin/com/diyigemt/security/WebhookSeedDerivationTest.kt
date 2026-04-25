package com.diyigemt.security

import com.diyigemt.arona.communication.WebhookBot
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Sprint 3.6 锁住 WebhookBot 构造期 seed 派生:
//  - 旧实现 secret.repeat(2).slice(0..31).toByteArray() 把 String 当 Char 数组切, 短 ASCII / 中文 / 代理对都会炸或得到错位 seed.
//  - 新实现按 UTF-8 字节循环拷贝到固定 32 字节, 保留"从 secret 决定性派生"语义.
// 任意一条用例失败都说明回退到了旧实现或边界 require 被吞了.
class WebhookSeedDerivationTest {

  private fun roundTripPayload(secret: String) {
    val bot = WebhookBot(secret)
    val body = "ts-1234567890:{\"k\":\"v\"}".toByteArray(Charsets.UTF_8)
    val sig = bot.webHookSign(body)
    assertTrue(bot.webHookVerify(body, sig), "secret '$secret' round-trip 失败")
    val tampered = sig.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte() }
    assertFalse(bot.webHookVerify(body, tampered), "secret '$secret' 篡改应拒绝")
  }

  @Test
  fun `短 ASCII secret 旧版会 IndexOutOfBoundsException 新版正常`() {
    // 旧 secret.repeat(2).slice(0..31): "x"*2="xx", slice(0..31) IOOB.
    roundTripPayload("x")
  }

  @Test
  fun `恰好 16 ASCII secret 兼容旧行为`() {
    // 16 ASCII 是 BC 密钥的常见配置长度, 新旧实现在此长度上理应都能跑.
    roundTripPayload("0123456789abcdef")
  }

  @Test
  fun `中文 secret 旧版炸 新版正常`() {
    // "测试密钥" UTF-8 是 12 字节, 旧实现按 Char repeat=8 chars, slice(0..31) IOOB; 即使补长也会因
    // Char 直接 toByteArray 得到非 32-byte seed, 新实现按 UTF-8 byte 循环正常.
    roundTripPayload("测试密钥")
  }

  @Test
  fun `空 secret 显式抛 IllegalArgumentException`() {
    val ex = assertFailsWith<IllegalArgumentException> { WebhookBot("") }
    assertTrue(ex.message!!.contains("empty"), "异常文案需点明空 secret")
  }

  @Test
  fun `相同 secret 派生稳定 不依赖随机数`() {
    // seed 派生应只跟 secret 有关; 同 secret 两个 bot 的签名互验互通过.
    val a = WebhookBot("repeatable-secret")
    val b = WebhookBot("repeatable-secret")
    val body = "stable".toByteArray(Charsets.UTF_8)
    assertTrue(b.webHookVerify(body, a.webHookSign(body)), "同 secret 派生应得到同 keypair")
    assertTrue(a.webHookVerify(body, b.webHookSign(body)))
  }

  @Test
  fun `不同 secret 派生不同 keypair`() {
    val a = WebhookBot("alpha-secret")
    val b = WebhookBot("beta-secret")
    val body = "diff".toByteArray(Charsets.UTF_8)
    assertFalse(b.webHookVerify(body, a.webHookSign(body)), "不同 secret 必须互不信任")
  }
}
