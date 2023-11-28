package com.diyigemt.arona.database.image

import com.diyigemt.arona.database.AronaDatabase
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

@AronaDatabase
internal object ImageCacheTable : IdTable<String>(name = "ImageCache") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val uuid = text("uuid")
  val ttl = long("ttl")
}

internal class ImageCacheSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, ImageCacheSchema>(ImageCacheTable)
  val uuid by ImageCacheTable.uuid
  val ttl by ImageCacheTable.ttl
}
