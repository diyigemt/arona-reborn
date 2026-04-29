package com.diyigemt.arona.database

import com.diyigemt.arona.database.permission.UserTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column

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