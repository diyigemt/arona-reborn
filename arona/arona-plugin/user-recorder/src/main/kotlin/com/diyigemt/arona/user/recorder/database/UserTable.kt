package com.diyigemt.arona.user.recorder.database

import com.diyigemt.arona.utils.currentDateTime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

/**
 * 记录用户总数 / 非独立个体, 包括不同聊天环境下的唯一id
 */
object UserTable : IdTable<String>(name = "User") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val active = bool("active").clientDefault { true }
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间

  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

class User(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, User>(UserTable)

  val active by UserTable.active
  val registerTime by UserTable.registerTime
}
