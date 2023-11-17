package com.diyigemt.arona.arona.database.tarot

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

object TarotRecordTable: IdTable<String>(name = "TarotRecord") {
  override val id = text("id").entityId()
  val day: Column<Int> = integer("day")
  val tarot: Column<Int> = integer("tarot")
  val positive: Column<Boolean> = bool("positive")

  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

class TarotRecord(id: EntityID<String>): Entity<String>(id) {
  companion object: EntityClass<String, TarotRecord>(TarotRecordTable)
  var day by TarotRecordTable.day
  var tarot by TarotRecordTable.tarot
  var positive by TarotRecordTable.positive
}