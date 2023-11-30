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
      dbQuery {
        when (Contact.find { ContactTable.id eq it.subject.id }.firstOrNull()) {
          is Contact -> { }
          else -> {
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
      dbQuery {
        when (val user = User.find { UserTable.id eq sender.id }.firstOrNull()) {
          is User -> { user.actionCount++ }
          else -> {
            User.new(
              id = sender.id
            ) { }
          }
        }
      }
      val messageString =
        it.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return@subscribeAlways
      val commandStr =
        messageString.split(" ").toMutableList().removeFirstOrNull() ?: return@subscribeAlways
      val command =
        CommandManager.matchCommand(commandStr.replace("/", "")) as? AbstractCommand ?: return@subscribeAlways
      dbQuery {
        when (val fCommand = Command.find { CommandTable.name eq command.primaryName }.firstOrNull()) {
          is Command -> fCommand.count++
          else -> {
            Command.new {
              name = command.primaryName
              count = 1
            }
          }
        }
      }
    }
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (val subject = it.subject) {
        is com.diyigemt.arona.communication.contact.User -> {
          dbQuery {
            when (User.find { UserTable.id eq subject.id }.firstOrNull()) {
              is User -> { }
              else -> {
                User.new(
                  id = subject.id
                ) { }
              }
            }
          }
        }
      }
      dbQuery {
        when (val contact = Contact.find { ContactTable.id eq it.subject.id }.firstOrNull()) {
          is Contact -> {
            contact.active = when(it) {
              is TencentFriendAddEvent, is TencentGroupAddEvent, is TencentGuildAddEvent -> true
              else -> false
            }
          }
          else -> {
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
}
