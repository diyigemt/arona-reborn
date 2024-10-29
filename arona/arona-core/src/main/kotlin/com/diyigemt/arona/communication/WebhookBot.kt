package com.diyigemt.arona.communication

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec


internal open class WebhookBot {
  private val publicKey: PublicKey
  private val privateKey: PrivateKey
  private val signer: Signature
  private val verifier: Signature
  constructor(secret: String) {
    val pk = secret.repeat(2).slice(0 .. 31).toByteArray()
    val privateKeySpec = PKCS8EncodedKeySpec(pk)
    val keyFactory = KeyFactory.getInstance("Ed25519")
    privateKey = keyFactory.generatePrivate(privateKeySpec)
    publicKey = keyFactory.generatePublic(X509EncodedKeySpec(privateKey.encoded))
    signer = Signature.getInstance("Ed25519").apply {
      initSign(privateKey)
    }
    verifier = Signature.getInstance("Ed25519").apply {
      initVerify(publicKey)
    }
  }
  fun webHookVerify(body: ByteArray, sign: ByteArray): Boolean {
    verifier.update(body)
    return verifier.verify(sign)
  }
  fun webHookSign(body: ByteArray): ByteArray {
    signer.update(body)
    return signer.sign()
  }
}