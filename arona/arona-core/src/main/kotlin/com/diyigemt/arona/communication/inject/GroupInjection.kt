package com.diyigemt.arona.communication.inject

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.contact.EmptyMockGroupImpl
import com.diyigemt.arona.communication.contact.Group
import com.diyigemt.arona.communication.event.QuotedMessage
import com.diyigemt.arona.communication.event.TencentGroupMessageEvent
import com.diyigemt.arona.communication.event.broadcastAsGroupMessageEvent
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.TencentGroupMessageAuthorRaw
import com.diyigemt.arona.communication.message.TencentGroupMessageElementRaw
import com.diyigemt.arona.communication.message.TencentGroupMessageMentionRaw
import com.diyigemt.arona.communication.message.TencentGroupMessageRaw
import com.diyigemt.arona.communication.message.TencentMessageAttachmentRaw
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 注入消息的出站模式.
 * - [SEND]: 回复去掉伪 msg_id, 以群主动消息真实发到测试群.
 * - [CAPTURE]: 窗口内发往该群的消息与富媒体上传一律不出网, 由 core 造本地回执.
 */
enum class InjectionMode { SEND, CAPTURE }

/** 注入相关的本地拒绝. 刻意不是 TencentApiErrorException, 不会触发 arona 插件的"错误发生"群通知. */
class InjectionRejectedException(message: String) : IllegalStateException(message)

/**
 * 一次群消息注入的观察窗口, 由 [openGroupInjection] 打开.
 *
 * 窗口按 **群** 生效而不是按消息归属: 窗口存活期间发往该群的全部出站 (含插件脱离事件协程、不带 msg_id
 * 另发的消息) 都按 [mode] 处理. 这样不必把注入上下文跨协程传播, 代价是窗口期间该群的真人消息回复也会被
 * 一并截获或收集, 所以只应对专用测试群开窗口.
 *
 * 带 [id] 作 msg_id 的回复在窗口关闭/过期后不会以 msg_id 发往平台: send 模式本地拒绝, capture 模式继续截获.
 * capture 窗口关闭/过期后还有一段余波期 (captureTail), 期间该群出站仍被截获, 防止插件慢任务 (如审图) 在
 * 收集结束后才另发的消息真实出网. 余波期内可以开新窗口, 新窗口接管该群.
 */
class GroupInjectionWindow internal constructor(
  val bot: TencentBot,
  val groupId: String,
  val mode: InjectionMode,
  private val deadlineNanos: Long,
  private val captureTailNanos: Long,
) : AutoCloseable {
  /** 注入消息的伪 msg_id, 也是本窗口的标识. */
  val id: String = GroupInjections.ID_PREFIX + UUID.randomUUID().toString().replace("-", "")
  private val dispatched = AtomicBoolean(false)

  @Volatile
  private var closed = false

  @Volatile
  private var captureUntilNanos = deadlineNanos + captureTailNanos

  /** 可分发、send 模式下可真实发送注入回复. */
  val isActive: Boolean
    get() = !closed && System.nanoTime() - deadlineNanos < 0

  /** capture 模式下该群出站仍被截获 (存活期 + 余波期). */
  internal val isCapturing: Boolean
    get() = mode == InjectionMode.CAPTURE && System.nanoTime() - captureUntilNanos < 0

  /**
   * 合成一条群消息事件并广播, 与真实 webhook 群消息走同一映射 (@/引用/附件解析一致), 但绕过验签与幂等.
   * 每个窗口只能调用一次. 返回时广播已结束, 但插件另起的协程可能还在运行, 回复要靠 PostSend 事件收集.
   *
   * @param atBot 是否 @ 了 bot (对应 mentions.is_you). chatbot 等按 @ 判定必答的功能依赖它.
   * @param imageUrls 作为图片附件下发, 下游插件可能真实下载这些地址.
   */
  suspend fun dispatch(
    memberId: String,
    content: String,
    atBot: Boolean = true,
    username: String? = null,
    quoted: QuotedMessage? = null,
    imageUrls: List<String> = emptyList(),
  ) {
    if (!dispatched.compareAndSet(false, true)) throw InjectionRejectedException("注入窗口 $id 已经分发过")
    if (!isActive) throw InjectionRejectedException("注入窗口 $id 已关闭或过期")
    require(memberId.isNotBlank()) { "memberId must not be blank" }
    TencentGroupMessageRaw(
      id = id,
      author = TencentGroupMessageAuthorRaw(memberOpenid = memberId, username = username),
      content = content,
      timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8)).format(PlatformTimestampFormat),
      attachments = imageUrls.map { TencentMessageAttachmentRaw(it) }.ifEmpty { null },
      mentions = if (atBot) listOf(TencentGroupMessageMentionRaw(isYou = true)) else null,
      groupId = groupId,
      messageType = if (quoted != null) QUOTE_MESSAGE_TYPE else 0,
      msgElements = quoted?.let { listOf(TencentGroupMessageElementRaw(msgIdx = it.msgIdx, content = it.content)) },
    ).broadcastAsGroupMessageEvent(bot, eventId = "")
  }

  /** 幂等. send 窗口关闭后该群出站立即恢复正常; capture 窗口从此刻起再截获 captureTail. */
  override fun close() {
    if (closed) return
    closed = true
    val tailEnd = System.nanoTime() + captureTailNanos
    if (tailEnd - captureUntilNanos < 0) captureUntilNanos = tailEnd
    GroupInjections.release(this)
  }

  private companion object {
    // 与平台下发格式一致 (秒精度 + 偏移), OffsetDateTime.toString() 在整秒时会省略秒.
    val PlatformTimestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    const val QUOTE_MESSAGE_TYPE = 103
  }
}

/**
 * 打开 [groupId] 的注入窗口. 同一 bot 同一群同时只能有一个活动窗口, 否则抛 [InjectionRejectedException].
 * 调用方负责 [GroupInjectionWindow.close]; 忘记关闭时窗口在 [lifetime] 后自动失效.
 * [captureTail] 只对 capture 模式有意义, 见 [GroupInjectionWindow].
 */
fun TencentBot.openGroupInjection(
  groupId: String,
  mode: InjectionMode,
  lifetime: Duration,
  captureTail: Duration = 60.seconds,
): GroupInjectionWindow = GroupInjections.open(this, groupId, mode, lifetime, captureTail)

/** 本事件是否由 [GroupInjectionWindow.dispatch] 注入. 统计类插件可据此排除测试流量. */
val TencentGroupMessageEvent.isInjected: Boolean
  get() = GroupInjections.isInjectedId(message.sourceId)

internal sealed interface InjectionRoute {
  data object Normal : InjectionRoute
  data object Send : InjectionRoute
  data object Capture : InjectionRoute
  class Reject(val reason: String) : InjectionRoute
}

object GroupInjections {
  /** 注入消息伪 msg_id 的前缀. */
  const val ID_PREFIX = "arona-inject-"

  /** capture 模式本地回执 id 的前缀. */
  const val CAPTURE_RECEIPT_PREFIX = "arona-capture-"

  private val windows = ConcurrentHashMap<Pair<String, String>, GroupInjectionWindow>()

  fun isInjectedId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)

  /** 注入的伪 msg_id 与 capture 回执 id 都不是平台真实 id, 不能拿去调撤回等接口. */
  fun isReservedId(id: String?): Boolean = isInjectedId(id) || (id != null && id.startsWith(CAPTURE_RECEIPT_PREFIX))

  internal fun open(
    bot: TencentBot,
    groupId: String,
    mode: InjectionMode,
    lifetime: Duration,
    captureTail: Duration,
  ): GroupInjectionWindow {
    require(groupId.isNotBlank()) { "groupId must not be blank" }
    require(lifetime.isPositive()) { "lifetime must be positive" }
    require(!captureTail.isNegative()) { "captureTail must not be negative" }
    // 群缓存里若是 mockGroupMessage 留下的占位, 它的 sendMessage 不走 callMessageOpenApi, 截获与收集都会失效.
    if (bot.groups.getOrCreate(groupId) is EmptyMockGroupImpl) {
      throw InjectionRejectedException("群 $groupId 的缓存是 mockGroupMessage 占位, 无法注入")
    }
    val window = GroupInjectionWindow(
      bot,
      groupId,
      mode,
      System.nanoTime() + lifetime.inWholeNanoseconds,
      captureTail.inWholeNanoseconds,
    )
    windows.compute(bot.id to groupId) { _, old ->
      if (old != null && old.isActive) throw InjectionRejectedException("群 $groupId 已有进行中的注入窗口")
      window
    }
    return window
  }

  internal fun release(window: GroupInjectionWindow) {
    // capture 窗口留在表里撑过余波期, 由 [currentWindow] 惰性清理.
    if (!window.isCapturing) windows.remove(window.bot.id to window.groupId, window)
  }

  /** 该群仍在生效 (存活或 capture 余波) 的窗口. */
  private fun currentWindow(bot: TencentBot, groupId: String): GroupInjectionWindow? {
    val key = bot.id to groupId
    val window = windows[key] ?: return null
    if (window.isActive || window.isCapturing) return window
    windows.remove(key, window)
    return null
  }

  /**
   * 发往 [contact] 的消息该怎么出站. 注入 id 同时看 PreSend 前的 [original] 与改写后的 [chain],
   * listener 换掉消息链也不会让迟到的注入回复漏过判定.
   */
  internal fun route(contact: Contact, original: MessageChain, chain: MessageChain): InjectionRoute {
    val injectedId = listOf(chain.sourceId, chain.eventId, original.sourceId, original.eventId).firstOrNull(::isInjectedId)
    val window = (contact as? Group)?.let { currentWindow(it.bot, it.id) }
    if (injectedId != null) {
      return when {
        window == null || window.id != injectedId ->
          InjectionRoute.Reject("注入 $injectedId 的窗口已关闭或目标不符, 拒绝把伪 msg_id 发往平台")
        window.isCapturing -> InjectionRoute.Capture
        window.mode == InjectionMode.SEND && window.isActive -> InjectionRoute.Send
        else -> InjectionRoute.Reject("注入 $injectedId 的窗口已关闭, 拒绝迟到的回复")
      }
    }
    return if (window?.isCapturing == true) InjectionRoute.Capture else InjectionRoute.Normal
  }

  /** [contact] 是否处在 capture 窗口 (含余波期) 内, 是则富媒体上传也不出网. */
  internal fun capturesUploads(contact: Contact): Boolean =
    (contact as? Group)?.let { currentWindow(it.bot, it.id)?.isCapturing } == true
}
