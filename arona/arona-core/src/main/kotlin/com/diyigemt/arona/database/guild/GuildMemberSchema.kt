package com.diyigemt.arona.database.guild

import com.diyigemt.arona.database.AronaDatabase
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column


@AronaDatabase
object GuildMemberTable : IdTable<String>(name = "GuildMember") {
  override val id: Column<EntityID<String>> = text("id").entityId()
  val botId: Column<String> = text("bot")
  val guildId: Column<String> = text("guild")
  val channelId: Column<String> = text("channel")
  override val primaryKey: PrimaryKey = PrimaryKey(id, botId, guildId, channelId)
}

class GuildMemberSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, GuildMemberSchema>(GuildMemberTable)

  var botId by GuildMemberTable.botId
  var guildId by GuildMemberTable.guildId
  var channelId by GuildMemberTable.channelId
}