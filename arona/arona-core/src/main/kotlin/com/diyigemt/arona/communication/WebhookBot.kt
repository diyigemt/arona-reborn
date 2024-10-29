package com.diyigemt.arona.communication

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.util.InternalAPI
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext

internal class FakeWebsocket(
  override val coroutineContext: CoroutineContext
) : DefaultWebSocketSession {
  override val closeReason: Deferred<CloseReason?>
    get() = TODO("Not yet implemented")
  override var pingIntervalMillis: Long
    get() = TODO("Not yet implemented")
    set(value) {}
  override var timeoutMillis: Long
    get() = TODO("Not yet implemented")
    set(value) {}
  @InternalAPI
  override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) {
    TODO("Not yet implemented")
  }
  override val extensions: List<WebSocketExtension<*>>
    get() = TODO("Not yet implemented")
  override val incoming: ReceiveChannel<Frame>
    get() = TODO("Not yet implemented")
  override var masking: Boolean
    get() = TODO("Not yet implemented")
    set(value) {}
  override var maxFrameSize: Long
    get() = TODO("Not yet implemented")
    set(value) {}
  override val outgoing: SendChannel<Frame>
    get() = TODO("Not yet implemented")
  override suspend fun flush() {
    TODO("Not yet implemented")
  }
  @Deprecated("")
  override fun terminate() {
    TODO("Not yet implemented")
  }
}

internal class FakeHttpClientCall(client: HttpClient) : HttpClientCall(client)

internal open class WebhookBot {
  private val publicKey: AsymmetricKeyParameter
  private val privateKey: AsymmetricKeyParameter
  private val signer: Ed25519Signer
  private val verifier: Ed25519Signer
  constructor(secret: String) {
    val seed = secret.repeat(2).slice(0 .. 31).toByteArray()
    privateKey = Ed25519PrivateKeyParameters(seed)
    publicKey = privateKey.generatePublicKey()
    signer = Ed25519Signer().apply {
      init(true, privateKey)
    }
    verifier = Ed25519Signer().apply {
      init(false, publicKey)
    }
  }
  fun webHookVerify(body: ByteArray, sign: ByteArray): Boolean {
    verifier.update(body, 0, body.size)
    return verifier.verifySignature(sign)
  }
  fun webHookSign(body: ByteArray): ByteArray {
    signer.update(body, 0, body.size)
    return signer.generateSignature()
  }
}