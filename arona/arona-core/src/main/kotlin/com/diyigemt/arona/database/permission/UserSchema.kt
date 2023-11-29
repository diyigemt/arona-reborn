package com.diyigemt.arona.database.permission

import com.diyigemt.arona.database.AronaDatabase
import com.diyigemt.arona.utils.currentDateTime
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

@AronaDatabase
internal object UserTable : IdTable<String>(name = "User") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val username = text("username") // 用户自定义的昵称
  val from = text("from") // 来源的环境id
  val unionOpenId = text("union_id") // qq定义的唯一id
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal class UserSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, UserSchema>(UserTable)
  var username by UserTable.username
  val from by UserTable.from
  var unionOpenId by UserTable.unionOpenId
  val registerTime by UserTable.registerTime
}
