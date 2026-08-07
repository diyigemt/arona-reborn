package com.diyigemt.arona.database

import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.Column

@AronaDatabase
internal object SystemPropertiesTable : IdTable<String>(name = "SystemProperties") {
  // varchar 而非 text: MariaDB 不允许对 TEXT 建无前缀长度的主键, TEXT 定义会让全新部署建表失败.
  // 存量库须手动 ALTER 对齐 (MODIFY id VARCHAR(255) + ADD PRIMARY KEY), SchemaUtils 不修已存在的表.
  override val id: Column<EntityID<String>> = varchar("id", 255).entityId()
  val value = text("value")
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal class SystemPropertiesSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, SystemPropertiesSchema>(SystemPropertiesTable)
  var value by SystemPropertiesTable.value
}