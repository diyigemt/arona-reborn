package com.diyigemt.arona.test

import com.diyigemt.arona.communication.TencentApiErrorException
import com.diyigemt.arona.communication.event.GroupMessagePostSendEvent
import com.diyigemt.arona.communication.inject.GroupInjections
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentCustomKeyboard
import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.TencentOfflineMedia
import com.diyigemt.arona.communication.message.TencentTemplateMarkdown
import com.diyigemt.arona.communication.message.TencentTempleKeyboard
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

/** 一个测试群: [groupId] 是 group_openid, [members] 是成员别名 → member_openid. */
@Serializable
data class AgentTestGroup(
  val groupId: String = "",
  val members: Map<String, String> = emptyMap(),
)

/**
 * 落在 `config/com.diyigemt.arona.test/agent.yml`. AutoSavePluginData 会定时回写, 运行中改文件会被覆盖, 须停机后改.
 *
 * 只有这里登记过的 (群别名, 成员别名) 组合能被注入, 请求里不接受裸 openid.
 * 窗口期间该群的全部出站都按注入模式处理 (见 core GroupInjectionWindow), 所以只登记专用测试群.
 */
object AgentTestConfig : AutoSavePluginData("agent") {
  val enabled by value(false)
  /** 群别名 → 测试群. */
  val groups by value<Map<String, AgentTestGroup>>(emptyMap())
  /** 收到最后一条回复后静默多久算结束. */
  val idleMs by value(3_000L)
  /** 请求未指定时的总等待上限. */
  val timeoutMs by value(30_000L)
  /** 请求可指定的总等待上限的上限; 反向代理的读超时要比它长. */
  val maxTimeoutMs by value(120_000L)
  /** send 模式下, 分发前先往群里发一条"[agent 测试] 注入: ..."的提示, 让群里的人知道回复是怎么来的. */
  val announce by value(false)
  val maxContentChars by value(2_000)
  val maxImages by value(4)
  /** 单次注入最多收集的出站条数, 防止刷屏型回复撑大响应. */
  val maxMessages by value(50)
  /** 进程内同时进行的注入数上限 (同一群本来就只能有一个). */
  val maxConcurrent by value(4)
  /**
   * 允许作为图片附件的主机名 (精确匹配). 空列表 = 不接受附件.
   * 下游插件 (如 plana 审图) 会用生产机真实下载附件, 只登记受控的素材主机.
   */
  val imageHosts by value<List<String>>(emptyList())
  /** capture 窗口关闭后继续截获该群出站的秒数, 防止插件慢任务在收集结束后真实发出. */
  val captureTailSeconds by value(60L)
}

@Serializable
data class CapturedElement(
  val type: String,
  val text: String? = null,
  val url: String? = null,
  val data: JsonElement? = null,
)

@Serializable
data class CapturedMessage(
  /** 距注入开始的毫秒数 (PostSend 时刻). */
  val offsetMs: Long,
  val ok: Boolean,
  /** capture 模式下以 [GroupInjections.CAPTURE_RECEIPT_PREFIX] 开头, 不是平台 id. */
  val receiptId: String? = null,
  /** 是否作为注入消息的回复 (带注入 id); false 表示同群其他出站, 如插件另起协程主动发的消息. */
  val replyToInjection: Boolean,
  /** 正文与 markdown 的可读拼接. */
  val text: String,
  val elements: List<CapturedElement>,
  val error: String? = null,
)

private val elementJson = Json { encodeDefaults = false }

internal fun Message.toCapturedElement(): CapturedElement = when (this) {
  is PlainText -> CapturedElement("text", text = toString())
  is TencentCustomMarkdown -> CapturedElement("markdown", text = content)
  is TencentTemplateMarkdown -> CapturedElement(
    "markdown_template",
    text = id,
    data = buildJsonObject { params.forEach { p -> put(p.key, JsonArray(p.values.map(::JsonPrimitive))) } },
  )
  is TencentCustomKeyboard -> CapturedElement(
    "keyboard",
    data = runCatching { elementJson.encodeToJsonElement(TencentCustomKeyboard.serializer(), this) }.getOrNull(),
  )
  // serialization() 是 TODO(), 只能取 id.
  is TencentTempleKeyboard -> CapturedElement("keyboard_template", text = id)
  is TencentImage -> CapturedElement("image", url = url)
  is TencentOfflineMedia -> CapturedElement("media", text = mediaType.toString(), url = url)
  else -> CapturedElement(this::class.simpleName ?: "unknown", text = runCatching { toString() }.getOrNull())
}

internal fun Throwable.describe(): String {
  val head = this::class.simpleName ?: "Exception"
  val detail = when (this) {
    is TencentApiErrorException -> "code=${source.code} ${source.message} trace_id=${source.traceId}"
    else -> message.orEmpty()
  }
  return "$head: ${detail.take(500)}"
}

/** 一次注入期间收集发往目标群的 PostSend. */
internal class InjectionCollector(
  private val injectionId: String,
  private val start: TimeSource.Monotonic.ValueTimeMark,
  private val limit: Int,
) {
  private val messages = mutableListOf<CapturedMessage>()
  private var lastOffset: Long? = null

  /** (已收条数, 最后一条的 offsetMs) 的一致快照. */
  fun progress(): Pair<Int, Long?> = synchronized(messages) { messages.size to lastOffset }

  fun snapshot(): List<CapturedMessage> = synchronized(messages) { messages.toList() }

  fun offer(ev: GroupMessagePostSendEvent) {
    // 上一轮注入的迟到回复 (或其本地拒绝) 不算本轮. 无源消息无法区分轮次, 照单全收.
    val foreignInjection = listOf(ev.message.sourceId, ev.message.eventId)
      .any { GroupInjections.isInjectedId(it) && it != injectionId }
    if (foreignInjection) return
    val offset = start.elapsedNow().inWholeMilliseconds
    val captured = CapturedMessage(
      offsetMs = offset,
      ok = ev.exception == null,
      receiptId = ev.receipt?.id,
      replyToInjection = ev.message.sourceId == injectionId || ev.message.eventId == injectionId,
      text = ev.message.mapNotNull {
        when (it) {
          is PlainText -> it.toString()
          is TencentCustomMarkdown -> it.content
          else -> null
        }
      }.joinToString("\n"),
      elements = ev.message.map { it.toCapturedElement() },
      error = ev.exception?.describe(),
    )
    synchronized(messages) {
      if (messages.size >= limit) return
      messages += captured
      lastOffset = maxOf(lastOffset ?: 0L, offset)
    }
  }
}

/** (botId, groupId) → 当前收集器. 由 [PluginMain] 的 PostSend 监听投递. */
internal object InjectionCollectors {
  private val active = ConcurrentHashMap<Pair<String, String>, InjectionCollector>()

  fun register(botId: String, groupId: String, collector: InjectionCollector) {
    active[botId to groupId] = collector
  }

  fun unregister(botId: String, groupId: String, collector: InjectionCollector) {
    active.remove(botId to groupId, collector)
  }

  fun offer(ev: GroupMessagePostSendEvent) {
    active[ev.bot.id to ev.target.id]?.offer(ev)
  }
}
