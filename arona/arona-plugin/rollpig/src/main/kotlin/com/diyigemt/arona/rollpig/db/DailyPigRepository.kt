package com.diyigemt.arona.rollpig.db

import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType

/**
 * 用户每日小猪的数据访问层。全部走参数化 [org.jetbrains.exposed.v1.jdbc.JdbcTransaction.exec], 防注入。
 */
internal object DailyPigRepository {
  private fun text(value: String, length: Int = 64): Pair<IColumnType<*>, Any?> =
    VarCharColumnType(length) to value

  private fun long(value: Long): Pair<IColumnType<*>, Any?> = LongColumnType() to value

  /**
   * 确定并返回 (botId, userId, date) 当天的小猪 id。
   *
   * [candidatePigId] 是调用方预先随机出的候选(让本层不反向依赖猪池)。同一事务内先以
   * `ON CONFLICT DO NOTHING` 尝试写入候选, 再回查该主键的实际值:
   * - 当天首次触发 → 候选写入成功, 回查得到候选本身;
   * - 当天/并发已有记录 → 写入被忽略, 回查得到既存值。
   *
   * 因此并发的两次首触只会有一个候选落库, 两者最终都返回同一只猪。不能用 DO UPDATE,
   * 否则后到者会覆盖先到者已确定的结果。
   */
  suspend fun resolveTodayPig(
    botId: String,
    userId: String,
    date: String,
    candidatePigId: String,
  ): String = RollpigDatabase.dbQuery {
    exec(
      """
      INSERT INTO DailyPig(bot_id, user_id, draw_date, pig_id, created_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(bot_id, user_id, draw_date) DO NOTHING
      """.trimIndent(),
      listOf(
        text(botId),
        text(userId),
        text(date, 16),
        text(candidatePigId),
        long(System.currentTimeMillis())
      ),
      StatementType.INSERT
    )

    exec(
      "SELECT pig_id FROM DailyPig WHERE bot_id = ? AND user_id = ? AND draw_date = ?",
      listOf(text(botId), text(userId), text(date, 16)),
      StatementType.SELECT
    ) { rs -> if (rs.next()) rs.getString(1) else null }
      ?: error("写入后未查到今日小猪记录: bot=$botId, user=$userId, date=$date")
  }
}
