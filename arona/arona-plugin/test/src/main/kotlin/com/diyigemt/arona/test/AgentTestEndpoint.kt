package com.diyigemt.arona.test

import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.event.QuotedMessage
import com.diyigemt.arona.communication.inject.InjectionMode
import com.diyigemt.arona.communication.inject.InjectionRejectedException
import com.diyigemt.arona.communication.inject.openGroupInjection
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.utils.badRequest
import com.diyigemt.arona.utils.errorMessage
import com.diyigemt.arona.utils.forbidden
import com.diyigemt.arona.utils.success
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointGet
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpointPost
import com.diyigemt.arona.webui.endpoints.isSuperAdmin
import com.diyigemt.arona.webui.plugins.receiveJsonOrNull
import io.ktor.server.application.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.net.URI
import kotlin.time.TimeSource

@Serializable
internal data class InjectQuote(val content: String = "", val msgIdx: String? = null)

@Serializable
internal data class InjectRequest(
  val group: String = "",
  val member: String = "",
  val content: String = "",
  /** send | capture, 必填, 不设默认值: 误发到真群要占配额且群里可见. */
  val mode: String = "",
  val at: Boolean = true,
  val username: String? = null,
  val quoted: InjectQuote? = null,
  val imageUrls: List<String> = emptyList(),
  val idleMs: Long? = null,
  val timeoutMs: Long? = null,
)

@Serializable
internal data class InjectResponse(
  val injectionId: String,
  val mode: String,
  /** idle: 最后一条回复后静默够久; timeout: 到总等待上限; limit: 收集条数到上限; dispatch_error: 分发抛异常. */
  val finishedBy: String,
  val elapsedMs: Long,
  /** 分发阶段抛出的异常 (监听器内部异常由 core 吞掉记日志, 不会出现在这里). */
  val dispatchError: String? = null,
  val messages: List<CapturedMessage>,
)

@Serializable
internal data class TargetGroup(val alias: String, val members: List<String>)

@Serializable
internal data class TargetsResponse(val enabled: Boolean, val groups: List<TargetGroup>)

/**
 * 给 AI agent 用的真群测试入口, 只有主配置 superAdminUid 里的用户 (webui 登录 token) 能调.
 * 插件拿不到 core 的 HaltPipeline, 所以每个 handler 自己判超管.
 *
 * POST /api/v1/test/agent/inject 是长轮询: 注入后一直收集到 idle/timeout/limit 才返回, 反向代理读超时要比
 * [AgentTestConfig.maxTimeoutMs] 长, 且不要对它做重试.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompletionExceptionOrNull
@Suppress("unused")
@AronaBackendEndpoint("/test/agent", withoutTransaction = true)
object AgentTestEndpoint {
  private val concurrency by lazy { Semaphore(AgentTestConfig.maxConcurrent.coerceAtLeast(1)) }

  @AronaBackendEndpointGet("/targets")
  suspend fun ApplicationCall.targets() {
    if (!isSuperAdmin) return forbidden()
    success(
      TargetsResponse(
        enabled = AgentTestConfig.enabled,
        groups = AgentTestConfig.groups.map { (alias, g) -> TargetGroup(alias, g.members.keys.sorted()) },
      )
    )
  }

  @AronaBackendEndpointPost("/inject")
  suspend fun ApplicationCall.inject() {
    if (!isSuperAdmin) return forbidden()
    val cfg = AgentTestConfig
    if (!cfg.enabled) return errorMessage("agent 测试未启用 (config/com.diyigemt.arona.test/agent.yml 的 enabled)")
    val req = receiveJsonOrNull<InjectRequest>() ?: return badRequest()
    val mode = when (req.mode.lowercase()) {
      "send" -> InjectionMode.SEND
      "capture" -> InjectionMode.CAPTURE
      else -> return errorMessage("mode 必须是 send 或 capture")
    }
    val group = cfg.groups[req.group] ?: return errorMessage("未登记的群别名: ${req.group}")
    val memberId = group.members[req.member] ?: return errorMessage("群 ${req.group} 未登记成员别名: ${req.member}")
    if (group.groupId.isBlank() || memberId.isBlank()) return errorMessage("群 ${req.group} 的 openid 配置为空")
    if (req.content.isBlank() && req.imageUrls.isEmpty()) return errorMessage("content 与 imageUrls 不能都为空")
    if (req.content.length > cfg.maxContentChars) return errorMessage("content 超过 ${cfg.maxContentChars} 字")
    if (req.imageUrls.size > cfg.maxImages) return errorMessage("imageUrls 最多 ${cfg.maxImages} 个")
    // 下游插件会用生产机真实下载附件, 只放行 https + 登记过的素材主机.
    req.imageUrls.firstOrNull { !isAllowedImage(it, cfg.imageHosts) }?.let {
      return errorMessage("imageUrls 只接受 https 且主机在 imageHosts 里的地址: $it")
    }
    val maxTimeoutMs = cfg.maxTimeoutMs.coerceAtLeast(1_000L)
    val timeoutMs = (req.timeoutMs ?: cfg.timeoutMs).coerceIn(1_000L, maxTimeoutMs)
    val idleMs = (req.idleMs ?: cfg.idleMs).coerceIn(200L, timeoutMs)
    val maxMessages = cfg.maxMessages.coerceAtLeast(1)

    val bot = BotManager.getBot()
    // shadow 下 OpenAPI 全部短路成假成功, send 的结果会误导人.
    if (mode == InjectionMode.SEND && bot.isShadow) return errorMessage("bot 处于 shadow 模式, send 不会真实发出, 请用 capture")

    if (!concurrency.tryAcquire()) return errorMessage("进行中的注入已达上限 ${cfg.maxConcurrent}")
    try {
      // 总时限从这里起算, 覆盖群提示与分发. 窗口比收集期稍长: 收集结束前窗口不能先失效.
      val start = TimeSource.Monotonic.markNow()
      val window = try {
        bot.openGroupInjection(
          group.groupId,
          mode,
          timeoutMs.milliseconds + 5.seconds,
          captureTail = cfg.captureTailSeconds.coerceAtLeast(0L).seconds,
        )
      } catch (e: InjectionRejectedException) {
        return errorMessage(e.message ?: "无法打开注入窗口")
      }
      val response = window.use {
        if (mode == InjectionMode.SEND && cfg.announce) {
          // 提示先于收集器注册, 不混进结果; 失败就不注入, 免得群里只看到没头没尾的回复.
          val notice = "[agent 测试] 注入: " + req.content.replace(Regex("\\s+"), " ").take(60)
          val receipt = runCatching { bot.groups.getOrCreate(group.groupId).sendMessage(PlainText(notice)) }
            .getOrElse { if (it is CancellationException) throw it; null }
          if (receipt == null) return errorMessage("send 模式的群提示发送失败, 已放弃注入")
        }
        val collector = InjectionCollector(window.id, start, maxMessages)
        InjectionCollectors.register(bot.id, group.groupId, collector)
        try {
          // 广播会等同步 listener 跑完, 放到插件作用域里异步执行, 慢 listener 也拖不过总时限.
          // 超时不取消它: 中途取消 listener 的副作用不可预期, 窗口关闭后它的迟到出站由 core 拒绝或截获.
          val dispatchJob = PluginMain.async {
            window.dispatch(
              memberId = memberId,
              content = req.content,
              atBot = req.at,
              username = req.username,
              quoted = req.quoted?.let { QuotedMessage(it.content, it.msgIdx) },
              imageUrls = req.imageUrls,
            )
          }
          val finishedBy = awaitQuiet(collector, dispatchJob, start, idleMs, timeoutMs, maxMessages)
          val dispatchError = when {
            !dispatchJob.isCompleted -> "分发在时限内未返回 (同步 listener 仍在运行)"
            else -> dispatchJob.getCompletionExceptionOrNull()?.describe()
          }
          InjectResponse(
            injectionId = window.id,
            mode = req.mode.lowercase(),
            finishedBy = finishedBy,
            elapsedMs = start.elapsedNow().inWholeMilliseconds,
            dispatchError = dispatchError,
            messages = collector.snapshot(),
          )
        } finally {
          InjectionCollectors.unregister(bot.id, group.groupId, collector)
        }
      }
      success(response)
    } finally {
      concurrency.release()
    }
  }

  /**
   * 首条回复之前一直等到 [timeoutMs] (命令可能要先渲染图片); 分发返回且已有回复之后, 最后一条后静默 [idleMs] 即结束.
   * chatbot 分段发送的间隔可能到秒级, 测多段回复时请调大 idleMs.
   */
  private suspend fun awaitQuiet(
    collector: InjectionCollector,
    dispatchJob: Job,
    start: TimeSource.Monotonic.ValueTimeMark,
    idleMs: Long,
    timeoutMs: Long,
    limit: Int,
  ): String {
    while (true) {
      val now = start.elapsedNow().inWholeMilliseconds
      if (now >= timeoutMs) return "timeout"
      // 分发本身抛了异常 (如窗口已失效), 不会再有回复.
      if (dispatchJob.isCancelled) return "dispatch_error"
      val (count, last) = collector.progress()
      if (count >= limit) return "limit"
      if (dispatchJob.isCompleted && last != null && now - last >= idleMs) return "idle"
      delay(50)
    }
  }

  private fun isAllowedImage(url: String, hosts: List<String>): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.host != null && uri.host.lowercase() in hosts.map { it.lowercase() }
  }
}
