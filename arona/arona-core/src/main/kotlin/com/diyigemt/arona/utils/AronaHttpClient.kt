package com.diyigemt.arona.utils

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
import io.ktor.client.engine.java.Java
import io.ktor.util.logging.KtorSimpleLogger

private val logger = KtorSimpleLogger("AronaHttpClient")

/**
 * 进程级出站代理配置, null = 未启用. 插件 (含 COS 等非 Ktor SDK) 需要 host/port 时读这里,
 * 不要自行解析配置. 首次读取打一行日志, 让启动日志留下实际出网路由的证据.
 */
val aronaHttpProxy: HttpProxyConfig? by lazy {
  aronaConfig.httpProxy.also {
    if (it == null) logger.info("出站代理未配置, 直连")
    else logger.info("出站代理启用: ${it.host}:${it.port}")
  }
}

/**
 * 统一出站 HttpClient 工厂: core 负责 engine 与全局代理, 调用方在 [block] 里配置
 * HttpTimeout / ContentNegotiation 等 client 行为; 返回的 client 生命周期由调用方持有.
 * 代理是调用方不可覆盖的 core 不变量 ([block] 签名不暴露 engine DSL).
 *
 * 代理模式用 Java 引擎而非 CIO: CIO 只要配了代理, 即使 HTTPS 已建 CONNECT 隧道,
 * 隧道内请求行仍发绝对形式 (GET https://host/path), 腾讯 multimedia CDN 对此回 404
 * (实测同 URL origin-form 200 / absolute-form 404); JDK HttpClient 发标准 origin-form.
 */
fun aronaHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
  when (val p = aronaHttpProxy) {
    null -> HttpClient(CIO) { block() }
    else -> HttpClient(Java) {
      block()
      engine {
        // 钉死 HTTP/1.1 对齐 CIO 行为, 避免经代理协商 h2 引入未验证的协议差异.
        protocolVersion = java.net.http.HttpClient.Version.HTTP_1_1
        proxy = ProxyBuilder.http("http://${p.host}:${p.port}")
      }
    }
  }
