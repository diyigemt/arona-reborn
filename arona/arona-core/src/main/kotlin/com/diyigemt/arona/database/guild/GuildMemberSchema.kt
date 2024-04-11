package com.diyigemt.arona.database.guild

import com.diyigemt.arona.database.AronaDatabase
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column


@AronaDatabase
internal object GuildMemberTable : IdTable<String>(name = "GuildMember") {
  override val id: Column<EntityID<String>> = char("id", 255).entityId()
  val botId: Column<String> = char("bot", 255)
  val guildId: Column<String> = char("guild", 255)
  val channelId: Column<String> = char("channel", 255)
  override val primaryKey: PrimaryKey = PrimaryKey(id)
}

internal class GuildMemberSchema(id: EntityID<String>) : Entity<String>(id) {
  companion object : EntityClass<String, GuildMemberSchema>(GuildMemberTable)
  var botId by GuildMemberTable.botId
  var guildId by GuildMemberTable.guildId
  var channelId by GuildMemberTable.channelId
}
