package com.diyigemt.arona.database

import com.diyigemt.arona.database.permission.UserTable
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

@AronaDatabase
internal object SystemPropertiesTable : IdTable<String>(name = "SystemProperties") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val value = text("value")
  override val primaryKey: PrimaryKey = PrimaryKey(UserTable.id)
}

internal class SystemPropertiesSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, SystemPropertiesSchema>(SystemPropertiesTable)
  var value by SystemPropertiesTable.value
}