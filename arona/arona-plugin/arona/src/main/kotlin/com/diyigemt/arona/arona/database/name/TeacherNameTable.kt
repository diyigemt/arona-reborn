package com.diyigemt.arona.arona.database.name

import com.diyigemt.arona.arona.database.Database
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

@Database
object TeacherNameTable: IdTable<String>(name = "TeacherName") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val name: Column<String> = char("name", 20)

  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

class TeacherName(id: EntityID<String>): Entity<String>(id) {
  companion object: EntityClass<String, TeacherName>(TeacherNameTable)
  var name by TeacherNameTable.name
}
