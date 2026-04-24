package com.diyigemt.arona.communication

import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

// Sprint 2.1 Part B: 删除 FakeWebsocket / FakeHttpClientCall (TencentBotClient 的 fake session 桥接随
// WS op/session 层一起下线). WebhookBot 保留 Ed25519 签名能力, 其它附属 ktor/coroutine import 一并清除.
internal open class WebhookBot {
  private val publicKey: AsymmetricKeyParameter
  private val privateKey: AsymmetricKeyParameter

  constructor(secret: String) {
    val seed = secret.repeat(2).slice(0 .. 31).toByteArray()
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
