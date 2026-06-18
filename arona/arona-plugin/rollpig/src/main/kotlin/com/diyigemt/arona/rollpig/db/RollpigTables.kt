package com.diyigemt.arona.rollpig.db

import org.jetbrains.exposed.v1.core.Table

/**
 * 用户每日抽取结果。
 *
 * 主键 `(bot_id, user_id, draw_date)`: 同一 bot 下同一用户在同一(北京时间)日期只保留一条,
 * 跨群/私聊共享同一只猪; 不同 bot 即使用户 id 字符串相同也互不串台。
 */
internal object DailyPigTable : Table("DailyPig") {
  val botId = varchar("bot_id", 64)
  val userId = varchar("user_id", 64)
  val drawDate = varchar("draw_date", 16) // ISO yyyy-MM-dd
  val pigId = varchar("pig_id", 64)
  val createdAt = long("created_at")

  override val primaryKey = PrimaryKey(botId, userId, drawDate, name = "PK_DailyPig")
}
