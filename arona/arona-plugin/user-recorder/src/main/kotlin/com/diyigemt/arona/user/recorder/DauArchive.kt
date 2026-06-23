package com.diyigemt.arona.user.recorder

import kotlinx.datetime.LocalDate

// Redis 数据模型 (date = ISO yyyy-MM-dd, 取系统默认时区):
//   dau.{date}.dau     hash uid -> 当日消息数    (按天, 可归档)
//   dau.{date}.contact hash cid -> 当日消息数    (按天, 可归档)
//   dau.{date}.message string  当日消息总数       (按天, 可归档)
//   dau.{date}.command hash cmd -> 当日执行次数  (按天, 可归档)
//   dau.command        hash cmd -> 累计执行次数  (累计, 永不归档/删除)
//   dau.user           hash uid -> 最后交互时间  (累计, 永不归档/删除)
//   dau.contact        hash cid -> 最后交互时间  (累计, 永不归档/删除)
// 消息处理器始终用 currentDate() (今天) 作为 key, 不用消息自身时间戳, 因此往日的 dau.{date}.* 一旦跨天即不再被写入。

fun dayDauKey(date: String) = "dau.$date.dau"
fun dayContactKey(date: String) = "dau.$date.contact"
fun dayMessageKey(date: String) = "dau.$date.message"
fun dayCommandKey(date: String) = "dau.$date.command"

const val UserKey = "dau.user"
const val ContactKey = "dau.contact"
const val CommandKey = "dau.command"

/**
 * 单次归档调度的关联上下文。[id] 为本进程内单调递增的 runId, 把一次调度从开始、扫描、单日 read/del 到最终汇总/异常
 * 的所有日志串成一条链; [trigger] 区分 startup / daily。仅用于日志关联, 不参与归档逻辑。
 */
internal data class ArchiveRun(val id: Long, val trigger: String)

/**
 * 某一天的 DAU 聚合摘要 (仅计数, 不含 uid/cid 明细)。既是归档写入单元, 也是历史展示读取单元。
 */
internal data class DauDailySummary(
  val date: String,
  val message: Long,
  val userCount: Long,
  val contactCount: Long,
  val command: Map<String, Long>,
)

/**
 * 从 Redis 读出的当日摘要 + 该日数据是否仍真实存在于 Redis。
 * [present] 用于区分 "未归档/删除失败仍在 Redis" 与 "已归档删除 / 从未有数据"。
 */
internal data class RedisDailySummary(
  val present: Boolean,
  val summary: DauDailySummary,
)

internal object DauArchiveKeys {
  // 严格匹配按天 key: 仅认四个已知后缀, 防止把累计键 (dau.user/dau.contact/dau.command) 或未来新增键误判为可归档。
  private val DailyKeyRegex = Regex("""^dau\.(\d{4}-\d{2}-\d{2})\.(dau|contact|message|command)$""")

  /**
   * 若 [key] 是早于 [today] 的合法按天 key, 返回其日期; 否则 null。
   * 非法日历日期 (如 2025-02-30) 由 [LocalDate.parse] 拒绝。
   */
  fun parseArchivableDate(key: String, today: LocalDate): LocalDate? {
    val raw = DailyKeyRegex.matchEntire(key)?.groupValues?.get(1) ?: return null
    val date = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
    return date.takeIf { it < today }
  }

  /** 某天的四个按天 key, 用于一次性 DEL。 */
  fun dailyKeys(date: String): Array<String> =
    arrayOf(dayDauKey(date), dayContactKey(date), dayMessageKey(date), dayCommandKey(date))
}

/**
 * 把 kreds hgetAll 返回的交替列表 [k0, v0, k1, v1, ...] 解析为 field -> Long。
 * 奇数长度或非数字值都视为数据异常抛出, 让归档/读取失败而非静默丢数。
 */
internal fun decodeCountHash(flat: List<String>): Map<String, Long> {
  require(flat.size % 2 == 0) { "redis hash response has odd element count: ${flat.size}" }
  return flat.chunked(2).associate { (field, value) ->
    field to (value.toLongOrNull() ?: error("non-numeric hash value for field '$field': '$value'"))
  }
}
