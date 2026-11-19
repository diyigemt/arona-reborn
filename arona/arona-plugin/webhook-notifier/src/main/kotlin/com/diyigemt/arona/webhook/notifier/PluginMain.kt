package com.diyigemt.arona.webhook.notifier

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.message.TencentCustomMarkdown
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
import kotlinx.serialization.Serializable

/**
 * 一个推送目标, 在 [WebhookConfig.targets] 里以别名为键.
 *
 * 配置按宽松模式加载, 字段名写错会静默取默认值. 所以默认值都选会被校验拒绝的值,
 * 尤其 [allowedIps] 默认空列表即配置错误, 不限来源必须显式写 `["*"]`: 拼错键名时白名单不会悄悄变成不限.
 */
@Serializable
data class WebhookTarget(
  /** 目标群的 group_openid. */
  val groupId: String = "",
  /** 要 @ 的成员的 member_openid, 留空则不 @. */
  val userId: String = "",
  /** 与 Authorization 请求头整体比较的完整值, 含 scheme, 如 `Bearer xxx`. 只支持可打印 ASCII, 各目标不能重复. */
  val authorization: String = "",
  /** 允许的来源 IP 或 CIDR; `*` 表示不限. */
  val allowedIps: List<String> = emptyList(),
  /** 本目标每分钟最多转发条数, 和全局限流同时生效. */
  val rateLimitPerMinute: Long = 20,
) {
  // 配置变更时框架会把值打进日志, 不能带出凭证.
  override fun toString() =
    "WebhookTarget(groupId=$groupId, userId=$userId, authorization=***, allowedIps=$allowedIps, rateLimitPerMinute=$rateLimitPerMinute)"
}

/**
 * 落在 `config/com.diyigemt.arona.webhook.notifier/config.yml`, 改动需重启生效.
 * 配置有误时不启动监听.
 */
object WebhookConfig : AutoSavePluginData("config") {
  /** 默认只听本机: 明文 HTTP 会暴露凭证, 对外应经反向代理加 TLS. */
  val host by value("127.0.0.1")
  val port by value(12356)
  /** 别名 → 目标, 调用路径为 `/webhook/{别名}`. */
  val targets by value<Map<String, WebhookTarget>>(emptyMap())
  /** 受信的反向代理地址或 CIDR. socket 对端在其中时, 从 X-Forwarded-For 求真实来源; 不经反代时设为空列表. */
  val trustedProxies by value(listOf("127.0.0.1", "::1"))
  /** 请求体上限, 超出返回 413. */
  val maxBodyBytes by value(64L * 1024)
  /** 消息正文上限, 超出截断后仍然发送. */
  val maxContentChars by value(2_000)
  /** 全局每分钟最多转发条数, 超出返回 429. */
  val rateLimitPerMinute by value(20L)
  val sendTimeoutMillis by value(10_000L)
}

private const val REQUEST_READ_TIMEOUT_SECONDS = 30
private const val MAX_BODY_BYTES_LIMIT = 16L * 1024 * 1024
internal const val ANY_IP = "*"
internal const val MAX_RATE_PER_MINUTE = 10_000L

/** 单个 token, 或 scheme 与 token 之间恰好一个空格; 都只能是可见 ASCII. */
private val AUTHORIZATION_PATTERN = Regex("[!-~]+( [!-~]+)?")

/** 逐个目标校验, 再查凭证是否重复. 返回空列表表示可以启动. */
internal fun targetsProblems(targets: Map<String, WebhookTarget>): List<String> =
  targets.flatMap { (alias, target) -> targetProblems(alias, target) } + duplicateCredentials(targets)

private fun targetProblems(alias: String, target: WebhookTarget): List<String> = with(target) {
  listOfNotNull(
    "别名只能由字母、数字、_ 和 - 组成, 最长 32".takeIf { !ALIAS_PATTERN.matches(alias) },
    "groupId 未配置".takeIf { groupId.isBlank() },
    "authorization 未配置".takeIf { authorization.isEmpty() },
    "authorization 只能是 \"scheme token\" (中间恰好一个空格) 或单个 token, 且只含可见 ASCII"
      .takeIf { authorization.isNotEmpty() && !AUTHORIZATION_PATTERN.matches(authorization) },
    "allowedIps 为空; 不限来源请写 [\"$ANY_IP\"]".takeIf { allowedIps.isEmpty() },
    // 混写时 * 会让列出的网段失效: 从不限收紧到指定网段却忘了删 *, 白名单就形同虚设.
    "allowedIps 里的 \"$ANY_IP\" 不能和具体地址混写".takeIf { ANY_IP in allowedIps && allowedIps.size > 1 },
    "rateLimitPerMinute 必须在 1 到 $MAX_RATE_PER_MINUTE 之间".takeIf { rateLimitPerMinute !in 1..MAX_RATE_PER_MINUTE },
  ) + allowedIps.filter { it != ANY_IP && IpRange.parse(it) == null }.map { "allowedIps 有无法解析的条目: $it" }
}.map { "targets.$alias: $it" }

/** 共用凭证会让一个调用方能推到别人的群, 目标隔离就没了. 只换 scheme 不换 token 也算共用. */
private fun duplicateCredentials(targets: Map<String, WebhookTarget>): List<String> =
  targets.entries
    .filter { it.value.authorization.isNotEmpty() }
    .groupBy { it.value.authorization.substringAfter(' ') }
    .values.filter { it.size > 1 }
    .map { group -> "targets ${group.joinToString { it.key }} 使用了相同的 token" }

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
      .onSuccess {
        logger.info("webhook 监听 {}:{}{}/{别名}", WebhookConfig.host, WebhookConfig.port, WEBHOOK_PATH)
        WebhookConfig.targets.forEach { (alias, target) ->
          logger.info("webhook 目标 {}: 来源 {}", alias, target.allowedIps.takeUnless { ANY_IP in it } ?: "不限")
        }
      }
      .onFailure { logger.error("webhook 监听启动失败", it) }
  }

  private fun configProblems(): List<String> = with(WebhookConfig) {
    val global = listOfNotNull(
      "port 必须在 1 到 65535 之间".takeIf { port !in 1..65535 },
      "targets 未配置".takeIf { targets.isEmpty() },
      "maxBodyBytes 必须在 1 到 $MAX_BODY_BYTES_LIMIT 之间".takeIf { maxBodyBytes !in 1..MAX_BODY_BYTES_LIMIT },
      "maxContentChars 必须大于 0".takeIf { maxContentChars <= 0 },
      "rateLimitPerMinute 必须在 1 到 $MAX_RATE_PER_MINUTE 之间".takeIf { rateLimitPerMinute !in 1..MAX_RATE_PER_MINUTE },
      // 等待回执期间连接没有新数据可读, 超过读超时会被引擎打断成 408.
      "sendTimeoutMillis 必须在 1 到 ${REQUEST_READ_TIMEOUT_SECONDS * 1000 - 1} 之间"
        .takeIf { sendTimeoutMillis !in 1 until REQUEST_READ_TIMEOUT_SECONDS * 1000L },
    ) + trustedProxies.filter { IpRange.parse(it) == null }.map { "trustedProxies 有无法解析的条目: $it" }
    global + targetsProblems(targets)
  }

  private fun startServer() {
    val trusted = WebhookConfig.trustedProxies.map { IpRange.parse(it)!! }
    val targets = WebhookConfig.targets.mapValues { (_, target) ->
      ResolvedTarget(
        groupId = target.groupId,
        userId = target.userId,
        authorization = target.authorization,
        allowedIps = target.allowedIps.takeUnless { ANY_IP in it }?.map { IpRange.parse(it)!! },
        limiter = IpRateLimiter(target.rateLimitPerMinute, target.rateLimitPerMinute, 60),
      )
    }
    val globalLimiter = IpRateLimiter(WebhookConfig.rateLimitPerMinute, WebhookConfig.rateLimitPerMinute, 60)
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
          webhookModule(
            targets = targets,
            maxBodyBytes = WebhookConfig.maxBodyBytes,
            maxContentChars = WebhookConfig.maxContentChars,
            globalLimiter = globalLimiter,
            // 用 local 而不是 origin: origin 会被转发头插件改写, local 始终是 socket 对端.
            resolveClientIp = { resolveClientIp(it.local.remoteAddress, it.headers.getAll("X-Forwarded-For").orEmpty(), trusted) },
            audit = { logger.info(it) },
            send = ::forwardToGroup,
          )
        }
      },
    ).start(wait = false)
  }

  private suspend fun forwardToGroup(groupId: String, message: TencentCustomMarkdown): Boolean {
    // onLoad 早于 bot 注册, 启动窗口内 getBot 会抛异常, 此时按发送失败处理让调用方重试.
    val bot = runCatching { BotManager.getBot() }.getOrNull()
    val receipt = bot?.let {
      withTimeoutOrNull(WebhookConfig.sendTimeoutMillis) {
        it.groups.getOrCreate(groupId).sendMessage(message)
      }
    }
    return receipt != null
  }
}
