package com.diyigemt.arona.user.recorder.database

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

/**
 * 记录每日DAU
 */
object DailyActiveUserTable : IdTable<String>(name = "DAU") {
  override val id: Column<EntityID<String>> = text("date").entityId()
  val count: Column<Int> = integer("count").default(0) // 当日DAU
  val message: Column<Int> = integer("message").default(0) // 上行消息总量
  val contact: Column<Int> = integer("contact").default(0) // 当日活跃环境
  override val primaryKey = PrimaryKey(id)
}

class DailyActiveUser(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, DailyActiveUser>(DailyActiveUserTable)

  var count by DailyActiveUserTable.count
  var message by DailyActiveUserTable.message
  var contact by DailyActiveUserTable.contact
  override fun toString(): String {
    return "Record(dau=$count,contact:$contact,message:$message)"
  }
}
