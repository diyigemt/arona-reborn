package com.diyigemt.arona.arona.database.tarot

import com.diyigemt.arona.arona.database.Database
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column

@Database
object TarotTable : IntIdTable(name = "Tarot") {
  val name: Column<String> = varchar("name", 30)
  val positive: Column<String> = text("positive")
  val negative: Column<String> = text("negative")

}

class TarotSchema(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<TarotSchema>(TarotTable)

  val name by TarotTable.name
  val positive by TarotTable.positive
  val negative by TarotTable.negative
}
