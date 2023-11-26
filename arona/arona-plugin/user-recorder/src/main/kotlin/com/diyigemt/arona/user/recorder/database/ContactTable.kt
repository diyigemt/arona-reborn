package com.diyigemt.arona.user.recorder.database

import com.diyigemt.arona.utils.currentDateTime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

enum class ContactType {
  Private,
  Group,
  Channel,
  PrivateChannel
}

/**
 * 记录加入的群数/频道数
 */
object ContactTable : IdTable<String>(name = "Contact") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val type = enumerationByName<ContactType>("type", 20)
  val active = bool("active").clientDefault { true }
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

class Contact(id: EntityID<String>) : Entity<String>(id) {

  companion object : EntityClass<String, Contact>(ContactTable)
  var type by ContactTable.type
  var active by ContactTable.active
  val registerTime by ContactTable.registerTime
}
