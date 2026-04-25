package com.diyigemt.arona.communication

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

// Sprint 2.1 Part B: 删除 FakeWebsocket / FakeHttpClientCall (TencentBotClient 的 fake session 桥接随
// WS op/session 层一起下线). WebhookBot 保留 Ed25519 签名能力, 其它附属 ktor/coroutine import 一并清除.
private const val Ed25519SeedSize = 32

internal open class WebhookBot {
  private val publicKey: AsymmetricKeyParameter
  private val privateKey: AsymmetricKeyParameter

  constructor(secret: String) {
    // Sprint 3.6: 旧实现 secret.repeat(2).slice(0..31).toByteArray() 走 String.slice (按 Char) → toByteArray()
    // 三连击, 同时踩三种坑: (1) 短 ASCII 长度不足 32 直接 IndexOutOfBoundsException; (2) 含中文/多字节字符
    // 时 Char→UTF-8 byte 数 ≠ Char 数, 出来的 seed 不一定 32 字节, BC Ed25519PrivateKeyParameters 必炸;
    // (3) 含代理对的 Char 单独 toByteArray 会得到 ?-replacement bytes. 这里改成按 UTF-8 字节循环到固定 32 字节,
    // 保留"从 secret 决定性派生"的旧接口语义, 不做 KDF 升级 (那是另一项工作).
    val raw = secret.toByteArray(Charsets.UTF_8)
    require(raw.isNotEmpty()) { "webhook secret cannot be empty" }
    val seed = ByteArray(Ed25519SeedSize) { i -> raw[i % raw.size] }
    privateKey = Ed25519PrivateKeyParameters(seed)
    publicKey = privateKey.generatePublicKey()
  }

  // Ed25519Signer 的 update/generate/verify 共用内部 buffer, 共享实例在并发入口下会串 body.
  // 因此每次按需构造一次性实例; BC 参数对象本身并发只读安全, 可长期复用.
  private fun createSigner(): Ed25519Signer = Ed25519Signer().apply { init(true, privateKey) }
  private fun createVerifier(): Ed25519Signer = Ed25519Signer().apply { init(false, publicKey) }

  fun webHookVerify(body: ByteArray, sign: ByteArray): Boolean {
    val verifier = createVerifier()
    verifier.update(body, 0, body.size)
    return verifier.verifySignature(sign)
  }

  fun webHookSign(body: ByteArray): ByteArray {
    val signer = createSigner()
    signer.update(body, 0, body.size)
    return signer.generateSignature()
  }
}
