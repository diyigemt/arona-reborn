package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.contact.FriendUser
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * 流式消息正文类型. wire 值是腾讯 OpenAPI 契约, 不用 enum name 隐式序列化——未来重命名枚举项
 * 不应改变请求体.
 */
enum class TencentStreamContentType(val wire: String) {
  TEXT("text"),
  MARKDOWN("markdown"),
}

// input_state 的两个已知取值: 1=流式输出中, 10=输出完成(终止片). wire 形态待 sandbox 验证.
private const val StreamInputStateStreaming = 1
private const val StreamInputStateFinished = 10

// 官方被动回复窗口 60 分钟, 留 5 分钟余量做本地软护栏 (见 TencentFriendStreamSession KDoc).
private val StreamPassiveWindowSoftLimit = 55.minutes

/**
 * 流式消息单片请求体. 全部字段名与取值待 sandbox 实测回填 (计划"验证"节).
 *
 * 三个 nullable 字段显式 @EncodeDefault(NEVER): null 时整个 key 省略, 而非编码显式 "msg_id":null,
 * 避免干扰服务端的被动消息判定. NEVER 把该契约固化在 DTO 自身——即便未来某个 TencentBot 实现
 * 的 json 配了 encodeDefaults=true 也不会破坏.
 */
@Serializable
internal data class TencentStreamMessageReq @OptIn(ExperimentalSerializationApi::class) constructor(
  @SerialName("input_state")
  val inputState: Int,
  val index: Int,
  @SerialName("content_type")
  val contentType: String,
  @SerialName("content_raw")
  val contentRaw: String,
  @SerialName("msg_seq")
  val messageSequence: Int,
  @SerialName("msg_id")
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val messageId: String? = null,
  @SerialName("event_id")
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val eventId: String? = null,
  @SerialName("stream_msg_id")
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val streamMessageId: String? = null,
  @SerialName("input_mode")
  @EncodeDefault
  val inputMode: String = "append",
  @SerialName("is_wakeup")
  @EncodeDefault
  val isWakeup: Boolean = false,
)

@Serializable
internal data class TencentStreamMessageExtInfo(
  @SerialName("ref_idx")
  val refIndex: Int? = null,
)

/**
 * 流式消息单片响应. timestamp/ref_idx/remain_msg_len 的实际类型待 sandbox 确认, 全字段给默认值
 * 以兼容缺字段响应; 但注意默认值救不了"已知字段类型不匹配" (与群成员列表 DTO 同一风险).
 */
@Serializable
internal data class TencentStreamMessageResp(
  val id: String = "",
  val timestamp: Long = 0L,
  @SerialName("ext_info")
  val extInfo: TencentStreamMessageExtInfo? = null,
  @SerialName("remain_msg_len")
  val remainMsgLen: Int? = null,
)

/**
 * 一次单聊流式消息会话, 由 [FriendUser.streamMessage] 创建并管理生命周期.
 *
 * 状态机 NEW → OPEN → (COMPLETED | FAILED), 所有 [emit]/[complete] 经 Mutex 串行化.
 * index 仅在服务端确认该片成功后推进; 首个非终止片必须取得非空响应 id 作为后续片的
 * stream_msg_id, 拿不到时立即闩锁失败——把空 id 当成功只会在下一片制造更难诊断的坏请求.
 *
 * 失败语义 (两条通道刻意不同):
 * - 任一片发送失败 → 会话闩锁 FAILED, 之后 emit/complete **返回**同一 failure 且不再出网.
 *   同 index 重试是否幂等未经证实, 不做自动重试; 40007 等领域错误原样透传.
 * - COMPLETED 之后再调 emit/complete → **抛** [IllegalStateException], 这是调用方编程错误.
 * - 发送期间协程取消 (含 withTimeout 的取消子类): 服务端是否已收到该片不可知, 先闩锁
 *   FAILED (cause 挂原始取消) 再原样重抛, 防止调用方捕获超时后用相同 index 盲目重发.
 *   生产 callOpenapi 用 runCatching 包 HTTP, 取消会以 Result.failure(CancellationException)
 *   形态到达, 此处同样还原为抛出.
 *
 * 被动窗口软护栏: 被动模式 (msg_id 或 event_id 非空) 下, 自会话创建起超过 55 分钟则本地
 * fail-fast, 不静默降级成主动消息. 注意它度量的是"会话创建后的存活时长"——现有事件模型不携带
 * 入站消息的接收时刻, 无法证明一个很晚才创建的会话仍在真实被动窗口内, 严格校验待上游把
 * 接收时间传进来. 主动模式 (两者皆空, 服务端是否放行未验证) 无窗口概念, 不设护栏.
 *
 * 本路径直接调用流式 OpenAPI, 不广播 MessagePreSendEvent/MessagePostSendEvent 审计链.
 * 调用方不应逐 token 调 [emit]——每片都是一次真实 HTTP 调用, 请在上游合并/节流分片;
 * 也不应把 block receiver 泄漏到 [FriendUser.streamMessage] 之外继续使用.
 *
 * 并发契约: Mutex 只保证互斥, 不保证提交顺序. block 内每次 [emit]/[complete] 必须顺序 await;
 * 未 join 的并发发送 (如 `launch { emit(...) }`) 会与自动收尾竞争锁, 终止片可能抢在分片之前.
 */
class TencentFriendStreamSession internal constructor(
  private val friend: FriendUser,
  sourceMessageId: String?,
  eventId: String?,
  private val messageSequence: Int,
  private val contentType: TencentStreamContentType,
  timeSource: TimeSource = TimeSource.Monotonic,
) {
  private enum class State { NEW, OPEN, COMPLETED, FAILED }

  // 空白与 null 归一: EmptyMessageId="" 这类占位值混进来不该被当成被动回执.
  private val sourceMessageId = sourceMessageId?.takeIf { it.isNotBlank() }
  private val eventId = eventId?.takeIf { it.isNotBlank() }
  private val isPassive = this.sourceMessageId != null || this.eventId != null
  private val createdAt = timeSource.markNow()
  private val mutex = Mutex()

  private var state = State.NEW
  private var nextIndex = 0
  private var latchedFailure: Throwable? = null

  @Volatile
  private var currentStreamMessageId: String? = null

  /**
   * 首个成功片响应携带的流消息 id, 仅诊断用; 首片尚未成功时为 null.
   */
  val streamMessageId: String? get() = currentStreamMessageId

  suspend fun emit(fragment: String): Result<Unit> = mutex.withLock {
    sendSliceLocked(fragment, terminal = false)
  }

  suspend fun complete(fragment: String = ""): Result<Unit> = mutex.withLock {
    sendSliceLocked(fragment, terminal = true)
  }

  /**
   * block 正常返回后的自动收尾: 与 public [complete] 不同, 已 COMPLETED 时返回 success,
   * 避免 block 内手工 complete 后被误判为重复终止.
   */
  internal suspend fun completeAfterBlock(): Result<Unit> = mutex.withLock {
    when (state) {
      State.NEW, State.OPEN -> sendSliceLocked("", terminal = true)
      State.COMPLETED -> Result.success(Unit)
      State.FAILED -> Result.failure(latchedFailure!!)
    }
  }

  private fun latchFailureLocked(cause: Throwable): Result<Unit> {
    state = State.FAILED
    latchedFailure = cause
    return Result.failure(cause)
  }

  private fun latchCancellationLocked(cancellation: CancellationException) {
    latchFailureLocked(
      IllegalStateException("stream slice cancelled mid-flight, delivery result unknown", cancellation)
    )
  }

  private suspend fun sendSliceLocked(fragment: String, terminal: Boolean): Result<Unit> {
    when (state) {
      State.COMPLETED -> throw IllegalStateException(
        "stream session already completed; emit/complete after completion is a caller bug"
      )

      State.FAILED -> return Result.failure(latchedFailure!!)
      State.NEW, State.OPEN -> Unit
    }
    if (isPassive && createdAt.elapsedNow() > StreamPassiveWindowSoftLimit) {
      return latchFailureLocked(
        IllegalStateException(
          "passive stream session exceeded the 55-minute local window guard; refusing to send"
        )
      )
    }
    val request = TencentStreamMessageReq(
      inputState = if (terminal) StreamInputStateFinished else StreamInputStateStreaming,
      index = nextIndex,
      contentType = contentType.wire,
      contentRaw = fragment,
      messageSequence = messageSequence,
      messageId = sourceMessageId,
      eventId = eventId,
      streamMessageId = currentStreamMessageId,
    )
    val result = try {
      friend.bot.callOpenapi(
        TencentEndpoint.PostFriendStreamMessage,
        TencentStreamMessageResp.serializer(),
        mapOf("openid" to friend.id),
      ) {
        method = HttpMethod.Post
        // 普通 data class 无 sealed 多态判别字段问题, 不需要 encodeTencentMessageForWire.
        setBody(friend.bot.json.encodeToString(TencentStreamMessageReq.serializer(), request))
      }
    } catch (e: CancellationException) {
      latchCancellationLocked(e)
      throw e
    } catch (e: Throwable) {
      return latchFailureLocked(e)
    }
    val response = result.getOrElse {
      if (it is CancellationException) {
        latchCancellationLocked(it)
        throw it
      }
      return latchFailureLocked(it)
    }
    if (!terminal && state == State.NEW && response.id.isBlank()) {
      return latchFailureLocked(
        IllegalStateException("first stream slice succeeded without a non-blank response id, cannot continue")
      )
    }
    if (currentStreamMessageId == null && response.id.isNotBlank()) {
      currentStreamMessageId = response.id
    }
    nextIndex += 1
    state = if (terminal) State.COMPLETED else State.OPEN
    return Result.success(Unit)
  }
}

/**
 * 创建并执行一个单聊流式消息会话 (仅 C2C 协议支持).
 *
 * block 正常返回且未手工 complete 时自动补发空终止片; block 抛 [CancellationException] 时原样
 * 重抛且**不**补终止片 (取消路径偷偷出网会掩盖调用方的取消意图), 其他异常转为 [Result.failure]
 * 同样不补终止片——注意这意味着 block 内的编程错误也会以 Result 形态返回而非抛出.
 *
 * 返回值: 会话以 COMPLETED 收尾 → success; 任一片失败/超窗/首片缺 id → 对应 failure.
 * 其余语义 (串行化/失败闩锁/窗口护栏/无审计链) 见 [TencentFriendStreamSession].
 */
suspend fun FriendUser.streamMessage(
  sourceMessageId: String? = null,
  eventId: String? = null,
  messageSequence: Int = 1,
  contentType: TencentStreamContentType = TencentStreamContentType.TEXT,
  block: suspend TencentFriendStreamSession.() -> Unit,
): Result<Unit> {
  val session = TencentFriendStreamSession(
    friend = this,
    sourceMessageId = sourceMessageId,
    eventId = eventId,
    messageSequence = messageSequence,
    contentType = contentType,
  )
  return try {
    session.block()
    session.completeAfterBlock()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    Result.failure(e)
  }
}
