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
  val count: Column<Int> = integer("count") // 当日DAU
  override val primaryKey = PrimaryKey(id)
}

class DailyActiveUser(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, DailyActiveUser>(DailyActiveUserTable)

  var count by DailyActiveUserTable.count
}
