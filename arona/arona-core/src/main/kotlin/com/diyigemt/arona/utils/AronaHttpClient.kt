package com.diyigemt.arona.utils

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.http
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
 * 代理在 [block] 之后注册 (engine block 按注册顺序链式执行), 是调用方不可覆盖的 core 不变量.
 */
fun aronaHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = HttpClient(CIO) {
  block()
  aronaHttpProxy?.let {
    engine {
      proxy = ProxyBuilder.http("http://${it.host}:${it.port}")
    }
  }
}
