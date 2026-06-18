package com.diyigemt.arona.plana.db

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType

internal data class RankRecord(val userId: String, val count: Int)

/**
 * 数据访问层. 计数/开关等并发写一律走 sqlite 原子 upsert(`ON CONFLICT ... DO UPDATE`),
 * 不做"读出来再内存自增", 以规避并发事件下的丢更新.
 *
 * 全部使用参数化 [org.jetbrains.exposed.v1.jdbc.JdbcTransaction.exec], 不拼接外部输入, 防注入.
 */
internal object PlanaRepository {
  private fun text(v: String): Pair<IColumnType<*>, Any?> = VarCharColumnType(64) to v
  private fun int(v: Int): Pair<IColumnType<*>, Any?> = IntegerColumnType() to v
  private fun long(v: Long): Pair<IColumnType<*>, Any?> = LongColumnType() to v
  private fun bool(v: Boolean): Pair<IColumnType<*>, Any?> = BooleanColumnType() to v
  private fun nowMillis() = System.currentTimeMillis()

  /** 命中缓存返回历史分数, 未命中返回 null. */
  suspend fun findScore(hash: String): Int? = PlanaDatabase.dbQuery {
    exec(
      "SELECT score FROM SensitiveCache WHERE hash = ?",
      listOf(text(hash)),
      StatementType.SELECT
    ) { rs -> if (rs.next()) rs.getInt(1) else null }
  }

  /** 写入/更新一条图片审查结果缓存. */
  suspend fun saveScore(
    hash: String,
    score: Int,
    label: String?,
    state: String?,
    result: Int?,
    sizeBytes: Int,
  ) {
    PlanaDatabase.dbQuery {
      exec(
        """
        INSERT INTO SensitiveCache(hash, score, label, state, result, size_bytes, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(hash) DO UPDATE SET
          score = excluded.score,
          label = excluded.label,
          state = excluded.state,
          result = excluded.result,
          size_bytes = excluded.size_bytes,
          updated_at = excluded.updated_at
        """.trimIndent(),
        listOf(
          text(hash),
          int(score),
          (VarCharColumnType(64) to label),
          (VarCharColumnType(32) to state),
          (IntegerColumnType() to result),
          int(sizeBytes),
          long(nowMillis())
        ),
        StatementType.INSERT
      )
    }
  }

  /** 原子地为 [userId] 累计 [delta] 次涩图记录. */
  suspend fun incrementRank(userId: String, delta: Int) {
    if (delta <= 0) return
    PlanaDatabase.dbQuery {
      exec(
        """
        INSERT INTO SeseRank(user_id, count, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(user_id) DO UPDATE SET
          count = count + excluded.count,
          updated_at = excluded.updated_at
        """.trimIndent(),
        listOf(text(userId), int(delta), long(nowMillis())),
        StatementType.INSERT
      )
    }
  }

  /** 排行总人数, 用于分页边界计算. */
  suspend fun rankTotal(): Long = PlanaDatabase.dbQuery {
    exec("SELECT COUNT(*) FROM SeseRank", emptyList(), StatementType.SELECT) { rs ->
      if (rs.next()) rs.getLong(1) else 0L
    } ?: 0L
  }

  /** 取第 [page](从 1 起)页、每页 [pageSize] 条, 按次数倒序、id 升序稳定排序. */
  suspend fun rankPage(page: Int, pageSize: Int): List<RankRecord> = PlanaDatabase.dbQuery {
    val offset = ((page - 1).coerceAtLeast(0)).toLong() * pageSize
    exec(
      "SELECT user_id, count FROM SeseRank ORDER BY count DESC, user_id ASC LIMIT ? OFFSET ?",
      listOf(int(pageSize), long(offset)),
      StatementType.SELECT
    ) { rs ->
      buildList { while (rs.next()) add(RankRecord(rs.getString(1), rs.getInt(2))) }
    } ?: emptyList()
  }

  /** 启动预热用: 读出所有显式记录过的群开关. */
  suspend fun listSwitches(): Map<String, Boolean> = PlanaDatabase.dbQuery {
    exec(
      "SELECT contact_id, enabled FROM AuditSwitch",
      emptyList(),
      StatementType.SELECT
    ) { rs ->
      buildMap { while (rs.next()) put(rs.getString(1), rs.getBoolean(2)) }
    } ?: emptyMap()
  }

  /** 持久化某群的审查开关状态. */
  suspend fun setSwitch(contactId: String, enabled: Boolean) {
    PlanaDatabase.dbQuery {
      exec(
        """
        INSERT INTO AuditSwitch(contact_id, enabled, updated_at)
        VALUES (?, ?, ?)
        ON CONFLICT(contact_id) DO UPDATE SET
          enabled = excluded.enabled,
          updated_at = excluded.updated_at
        """.trimIndent(),
        listOf(text(contactId), bool(enabled), long(nowMillis())),
        StatementType.INSERT
      )
    }
  }
}
