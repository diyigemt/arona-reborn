package com.diyigemt.security

import com.diyigemt.arona.communication.WebhookBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 回归保护: signer/verifier 过去是共享字段, 并发入口会串内部 buffer.
// 这里用 500 个协程 × 每个 10 轮的混跑, 稳定触发旧实现的串状态.
class WebhookSignerConcurrencyTest {
  private val bot = WebhookBot("0123456789abcdef")

  private fun payload(index: Int, round: Int = 0): ByteArray =
    "1713955200:{\"index\":$index,\"round\":$round}".toByteArray(Charsets.UTF_8)

  private fun ByteArray.withFlippedLastBit(): ByteArray = copyOf().apply {
    this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
  }

  @Test
  fun `正确签名能够被验签通过`() {
    val body = payload(1)
    val signature = bot.webHookSign(body)

    assertTrue(bot.webHookVerify(body, signature))
  }

  @Test
  fun `签名被篡改后验签应拒绝`() {
    val body = payload(2)
    val tampered = bot.webHookSign(body).withFlippedLastBit()

    assertFalse(bot.webHookVerify(body, tampered))
  }

  @Test
  fun `并发 sign 与 verify 之间不会串状态`() {
    runBlocking {
      coroutineScope {
        (1..500).map { index ->
          async(Dispatchers.Default) {
            repeat(10) { round ->
              val body = payload(index, round)
              val signature = bot.webHookSign(body)

              assertTrue(
                bot.webHookVerify(body, signature),
                "round-trip failed: index=$index round=$round",
              )
              assertFalse(
                bot.webHookVerify(body, signature.withFlippedLastBit()),
                "tampered signature unexpectedly accepted: index=$index round=$round",
              )
            }
          }
        }.awaitAll()
      }
    }
  }
}
