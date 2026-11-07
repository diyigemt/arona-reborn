package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 落在 `config/com.diyigemt.arona.webhook.notifier/config.yml`, 改动需重启生效.
 * [authorization] 或 [groupId] 为空时不启动监听.
 */
object WebhookConfig : AutoSavePluginData("config") {
  /** 默认只听本机: 明文 HTTP 会暴露 [authorization], 对外应经反向代理加 TLS. */
  val host by value("127.0.0.1")
  val port by value(12356)
  /** 与 Authorization 请求头整体比较的完整值, 含 scheme, 如 `Bearer xxx`. 只支持 ASCII. */
  val authorization by value("")
  /** 目标群的 group_openid. */
  val groupId by value("")
  /** 要 @ 的成员的 member_openid, 留空则只发到群里不 @. */
  val userId by value("")
  /** 请求体上限, 超出返回 413. */
  val maxBodyBytes by value(64L * 1024)
  /** 消息正文上限, 超出截断后仍然发送. */
  val maxContentChars by value(2_000)
  /** 全局每分钟最多转发条数, 超出返回 429. */
  val rateLimitPerMinute by value(20L)
  val sendTimeoutMillis by value(10_000L)
}

private const val REQUEST_READ_TIMEOUT_SECONDS = 30

@Suppress("unused")
object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION,
  ),
) {
  override fun onLoad() {
    val problems = configProblems()
    if (problems.isNotEmpty()) {
      logger.warn("配置有误, 不启动 webhook 监听: {}", problems)
      return
    }
    // onLoad 抛异常会终止整个 core 启动, 端口被占之类的问题只能影响本插件.
    runCatching { startServer() }
      .onSuccess { logger.info("webhook 监听 {}:{}{}", WebhookConfig.host, WebhookConfig.port, WEBHOOK_PATH) }
      .onFailure { logger.error("webhook 监听启动失败", it) }
  }

  private fun configProblems(): List<String> = with(WebhookConfig) {
    listOfNotNull(
      "authorization 未配置".takeIf { authorization.isBlank() },
      "groupId 未配置".takeIf { groupId.isBlank() },
      "maxBodyBytes 必须大于 0".takeIf { maxBodyBytes !in 1 until Long.MAX_VALUE },
      "maxContentChars 必须大于 0".takeIf { maxContentChars <= 0 },
      "rateLimitPerMinute 必须大于 0".takeIf { rateLimitPerMinute <= 0 },
      // 等待回执期间连接没有新数据可读, 超过读超时会被引擎打断成 408.
      "sendTimeoutMillis 必须在 1 到 ${REQUEST_READ_TIMEOUT_SECONDS * 1000 - 1} 之间"
        .takeIf { sendTimeoutMillis !in 1 until REQUEST_READ_TIMEOUT_SECONDS * 1000L },
    )
  }

  private fun startServer() {
    val limiter = IpRateLimiter(
      capacity = WebhookConfig.rateLimitPerMinute,
      refillTokens = WebhookConfig.rateLimitPerMinute,
      refillSeconds = 60,
    )
    embeddedServer(
      Netty,
      configure = {
        connector {
          host = WebhookConfig.host
          port = WebhookConfig.port
        }
        // Netty 默认不限读超时: 请求体传一半就停住的连接会一直占着. 空闲的长连接也一并回收, 对 webhook 无影响.
        requestReadTimeoutSeconds = REQUEST_READ_TIMEOUT_SECONDS
      },
      rootConfig = serverConfig {
        // 不跟随 core 的 io.ktor.development: 插件不需要类热重载.
        developmentMode = false
        module {
          webhookModule(WebhookConfig.authorization, WebhookConfig.maxBodyBytes, limiter, ::forwardToGroup)
        }
      },
    ).start(wait = false)
  }

  private suspend fun forwardToGroup(body: String): Boolean {
    // onLoad 早于 bot 注册, 启动窗口内 getBot 会抛异常, 此时按发送失败处理让调用方重试.
    val bot = runCatching { BotManager.getBot() }.getOrNull()
    val message = buildMessage(WebhookConfig.userId, body, WebhookConfig.maxContentChars)
    val receipt = bot?.let {
      withTimeoutOrNull(WebhookConfig.sendTimeoutMillis) {
        it.groups.getOrCreate(WebhookConfig.groupId).sendMessage(message)
      }
    }
    if (receipt == null) logger.warn("webhook 消息转发失败")
    return receipt != null
  }
}
