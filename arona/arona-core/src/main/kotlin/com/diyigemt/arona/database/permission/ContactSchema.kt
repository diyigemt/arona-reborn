package com.diyigemt.arona.database.permission

import com.diyigemt.arona.database.AronaDatabase
import com.diyigemt.arona.utils.currentDateTime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

enum class ContactType {
  Private,
  PrivateGuild,
  Group,
  Guild,
}

@AronaDatabase
internal object ContactTable : IdTable<String>(name = "User") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val contactName = text("contact_name") // 自定义的聊天特定名称, 仅公开频道和群有效
  val contactType = enumerationByName<ContactType>("type", 20)
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal class ContactSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, ContactSchema>(ContactTable)
  var contactName by ContactTable.contactName
  val contactType by ContactTable.contactType
  val registerTime by ContactTable.registerTime
}
