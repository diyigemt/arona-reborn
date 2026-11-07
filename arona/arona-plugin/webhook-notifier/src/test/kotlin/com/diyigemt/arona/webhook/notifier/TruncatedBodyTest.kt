package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** 连接中断只在真实引擎上才会发生, testApplication 的客户端也发不出半截请求, 所以这里用原始 socket. */
class TruncatedBodyTest {
  private val sent = CopyOnWriteArrayList<String>()

  private fun withServer(block: (port: Int) -> Unit) {
    val server = embeddedServer(
      Netty,
      configure = {
        connector {
          host = "127.0.0.1"
          port = 0
        }
      },
    ) {
      webhookModule(TOKEN, 1024, IpRateLimiter(10, 10, 60)) {
        sent += it
        true
      }
    }.start(wait = false)
    try {
      block(runBlocking { server.engine.resolvedConnectors() }.single().port)
    } finally {
      server.stop(0, 0)
    }
  }

  private fun sendRaw(port: Int, framing: String, body: String) {
    Socket("127.0.0.1", port).use {
      val head = "POST $WEBHOOK_PATH HTTP/1.1\r\nHost: x\r\nAuthorization: $TOKEN\r\n$framing\r\n\r\n"
      it.getOutputStream().write((head + body).toByteArray())
      it.getOutputStream().flush()
      // 给服务端时间读到这部分内容, 再断开连接.
      Thread.sleep(200)
    }
    Thread.sleep(300)
  }

  /** 同一个服务上再发一条完整请求: 证明路由是通的, 否则"没有转发"可能只是服务根本没工作. */
  private fun assertOnlyCompleteRequestForwarded(port: Int) {
    Socket("127.0.0.1", port).use {
      val head = "POST $WEBHOOK_PATH HTTP/1.1\r\nHost: x\r\nAuthorization: $TOKEN\r\nContent-Length: 8\r\n\r\n"
      it.getOutputStream().write((head + "complete").toByteArray())
      it.getOutputStream().flush()
      assertEquals("HTTP/1.1 200 OK", it.getInputStream().bufferedReader().readLine())
    }
    assertEquals(listOf("complete"), sent.toList())
  }

  @Test
  fun `body cut short of content length is not forwarded`() = withServer { port ->
    sendRaw(port, "Content-Length: 500", "partial")
    assertOnlyCompleteRequestForwarded(port)
  }

  @Test
  fun `chunked body without terminator is not forwarded`() = withServer { port ->
    sendRaw(port, "Transfer-Encoding: chunked", "7\r\npartial\r\n")
    assertOnlyCompleteRequestForwarded(port)
  }

  private companion object {
    const val TOKEN = "Bearer secret"
  }
}
