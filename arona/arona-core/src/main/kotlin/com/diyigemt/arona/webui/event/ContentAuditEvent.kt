package com.diyigemt.arona.webui.event

import com.diyigemt.arona.communication.event.Event
import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.utils.commandLineLogger
import kotlinx.coroutines.withTimeoutOrNull

data class ContentAuditEvent(
  var value: String,
  var pass: Boolean = true,
  var message: String = "Normal",
  val level: Int? = null // 屏蔽等级 越低屏蔽越严格
) : Event

inline val ContentAuditEvent.isBlock
  get() = !pass

inline val ContentAuditEvent.isPass
  get() = pass

/**
 * 包装一次内容审核, 将"审核插件慢/挂"与请求生命周期解耦.
 * 返回值:
 *   - 非 null: 审核完成, 调用方应检查 [isBlock] 决定是否拒绝;
 *   - null:    超时或异常发生; 是否当作"放行"取决于调用方对返回值的判定方式.
 *              本函数总是写一条 warn 日志, 不强制策略.
 *
 * 调用约定:
 *   - **fail-open** (默认): `if (audit?.isBlock == true) reject` —— 审核挂掉时放行.
 *     适用于"审核仅做兜底, 拦不到也能接受"的低风险字段 (如群配置).
 *   - **fail-closed**: `if (audit == null || audit.isBlock) reject` —— 审核挂掉时拒绝.
 *     适用于"宁可拒绝也不能漏" (如对外可见的用户名/资料).
 *
 * [auditor] 默认调用全局事件总线 [broadcast]; 可在测试中替换以注入故障行为.
 */
internal suspend fun auditOrAllow(
  content: String,
  level: Int = 60,
  timeoutMillis: Long = 3_000,
  auditor: suspend (ContentAuditEvent) -> ContentAuditEvent = { it.broadcast() },
): ContentAuditEvent? = try {
  withTimeoutOrNull(timeoutMillis) {
    auditor(ContentAuditEvent(content, level = level))
  }.also {
    if (it == null) {
      commandLineLogger.warn("content audit timeout after ${timeoutMillis}ms; caller decides fail-open/closed")
    }
  }
} catch (t: Throwable) {
  commandLineLogger.warn("content audit failed: ${t::class.simpleName}: ${t.message}; caller decides fail-open/closed")
  null
}
