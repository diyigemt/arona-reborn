package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.user.recorder.database.*
import com.diyigemt.arona.user.recorder.database.DatabaseProvider.dbQuery

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.user.recorder",
    name = "user-recorder",
    author = "diyigemt",
    version = "1.0.0",
    description = "record user data"
  )
) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentMessageEvent> {
      val messageString =
        it.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return@subscribeAlways
      val commandStr =
        messageString.split(" ").toMutableList().removeFirstOrNull() ?: return@subscribeAlways
      val command =
        CommandManager.matchCommand(commandStr.replace("/", "")) as? AbstractCommand ?: return@subscribeAlways
      dbQuery {
        Command.find { CommandTable.name eq command.primaryName }.firstOrNull()?.run {
          this.count++
        } ?: {
          Command.new {
            name = command.primaryName
            count = 1
          }
        }
      }
      dbQuery {
        Contact.find { ContactTable.id eq it.subject.id }.firstOrNull() ?: {
          Contact.new(it.subject.id) {
            type = when (it) {
              is TencentFriendEvent -> ContactType.Private
              is TencentGroupEvent -> ContactType.Group
              is TencentGuildEvent -> ContactType.Channel
              else -> ContactType.PrivateChannel
            }
          }
        }
      }
      dbQuery {
        User.find { UserTable.id eq sender.id }.firstOrNull() ?: {
          User.new(
            id = sender.id
          ) { }
        }
      }
    }
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (val subject = it.subject) {
        is com.diyigemt.arona.communication.contact.User -> {
          dbQuery {
            User.find { UserTable.id eq subject.id }.firstOrNull() ?: {
              User.new(
                id = subject.id
              ) { }
            }
          }
        }
      }
      dbQuery {
        Contact.find { ContactTable.id eq it.subject.id }.firstOrNull()?.also { schema ->
          schema.active = when(it) {
            is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> true
            else -> false
          }
        } ?: {
          Contact.new(it.subject.id) {
            type = when (it) {
              is TencentFriendEvent -> ContactType.Private
              is TencentGroupEvent -> ContactType.Group
              is TencentGuildEvent -> ContactType.Channel
              else -> ContactType.PrivateChannel
            }
          }
        }
      }
    }
  }
}
