package com.diyigemt.arona.arona.database.tarot

import com.diyigemt.arona.arona.database.Database
import com.diyigemt.arona.utils.currentLocalDateTime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

@Database
object TarotRecordTable : IdTable<String>(name = "TarotRecord") {
  override val id = char("id", 255).entityId()
  val day: Column<Int> = integer("day").clientDefault { currentLocalDateTime().date.dayOfMonth }
  val tarot: Column<Int> = integer("tarot").clientDefault { 0 }
  val positive: Column<Boolean> = bool("positive").clientDefault { true }
  val negativeCount: Column<Int> = integer("negative_count").clientDefault { 0 } // 统计连续逆位多少次了
  val maxNegativeCount: Column<Int> = integer("max_negative_count").clientDefault { 0 } // 统计最高连续逆位多少次

  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

class TarotRecordSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, TarotRecordSchema>(TarotRecordTable)

  var day by TarotRecordTable.day
  var tarot by TarotRecordTable.tarot
  var positive by TarotRecordTable.positive
  var negativeCount by TarotRecordTable.negativeCount
  var maxNegativeCount by TarotRecordTable.maxNegativeCount
}
