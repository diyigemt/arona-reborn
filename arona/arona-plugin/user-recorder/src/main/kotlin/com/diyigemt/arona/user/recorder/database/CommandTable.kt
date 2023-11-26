package com.diyigemt.arona.user.recorder.database

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column

/**
 * 记录指令总使用次数
 */
object CommandTable : IntIdTable(name = "Command") {
  val name: Column<String> = varchar("name", 30) // command name
  val count: Column<Long> = long("count") // call times
}

class Command(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<Command>(CommandTable)

  var name by CommandTable.name
  var count by CommandTable.count
}
