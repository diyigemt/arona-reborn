package com.diyigemt.arona.database

import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.datetimeToInstant
import com.diyigemt.arona.utils.now
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import java.util.*

@AronaDatabase
object UserTable : IdTable<UUID>(name = "user") {
  override val id: Column<EntityID<UUID>> = uuid("uuid").autoGenerate().entityId() // 唯一标识符
  val uuid get() = id
  val version = varchar("version", length = 50) // 客户端版本号
  val lifetime = long("lifetime").default(0) // 总在线时长
  val lastOnline = char("last_online", length = 25).clientDefault { currentDateTime() } // 最后交互时间
  val registerTime = char("register_time", length = 25).clientDefault { currentDateTime() } // 注册时间
  override val primaryKey = PrimaryKey(id)
}

class UserSchema(id: EntityID<UUID>) : UUIDEntity(id) {
  companion object : UUIDEntityClass<UserSchema>(UserTable)

  val uuid by UserTable.uuid
  var version by UserTable.version
  var lifetime by UserTable.lifetime
  var lastOnline by UserTable.lastOnline
  val registerTime by UserTable.registerTime
  fun updateOnline() {
    this.lastOnline = currentDateTime()
  }

  fun updateOffline() {
    val lastOnline = datetimeToInstant(this.lastOnline)
    this.lifetime += now().epochSeconds - lastOnline.epochSeconds
  }

  fun serialize() = UserSchemaDO(
    uuid.value.toString(),
    version,
    lifetime,
    lastOnline,
    registerTime
  )
}

@Serializable
data class UserSchemaDO(
  val uuid: String,
  val version: String,
  val lifetime: Long,
  val lastOnline: String,
  val registerTime: String
)
