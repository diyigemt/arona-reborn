package com.diyigemt.arona.plana.db

import org.jetbrains.exposed.v1.core.Table

/**
 * 图片审查结果缓存: 以图片内容 hash 为键, 复用历史审查分数, 避免重复打 COS.
 */
internal object SensitiveCacheTable : Table("SensitiveCache") {
  val hash = varchar("hash", 64)
  val score = integer("score")
  val label = varchar("label", 64).nullable()
  val state = varchar("state", 32).nullable()
  val result = integer("result").nullable()
  val sizeBytes = integer("size_bytes")
  val updatedAt = long("updated_at")
  override val primaryKey = PrimaryKey(hash)
}

/**
 * 涩图排行: 累计每个用户被判定为涩图的发送次数, 全局聚合(不分群、不分 bot).
 */
internal object SeseRankTable : Table("SeseRank") {
  val userId = varchar("user_id", 64)
  val count = integer("count")
  val updatedAt = long("updated_at")
  override val primaryKey = PrimaryKey(userId)
}

/**
 * 群审查开关: 仅记录显式开启过的群; 不存在的群默认关闭审查.
 */
internal object AuditSwitchTable : Table("AuditSwitch") {
  val contactId = varchar("contact_id", 64)
  val enabled = bool("enabled")
  val updatedAt = long("updated_at")
  override val primaryKey = PrimaryKey(contactId)
}
