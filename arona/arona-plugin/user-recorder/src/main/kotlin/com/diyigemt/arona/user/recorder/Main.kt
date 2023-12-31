package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.user.recorder.database.*
import com.diyigemt.arona.user.recorder.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.github.ajalt.clikt.core.CliktCommand
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.system.exitProcess

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.user.recorder",
    name = "user-recorder",
    author = "diyigemt",
    version = "1.0.2",
    description = "record user data"
  )
) {
  private val timer = Timer("user-recorder", true)
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentMessageEvent> {
      dbQuery {
        when (val contact = Contact.find { ContactTable.id eq it.subject.id }.firstOrNull()) {
          is Contact -> {
            contact.lastActive = currentDateTime()
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
      dbQuery {
        when (val user = User.find { UserTable.id eq sender.id }.firstOrNull()) {
          is User -> {
            user.actionCount
            user.lastActive = currentDateTime()
          }
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
    timer.scheduleAtFixedRate(0L, 10 * 60 * 1000L) {
      dbQuery {
        val today = currentDate()
        val c = User.find( UserTable.lastActive greater today ).count().toInt()
        when (val record = DailyActiveUser.findById(today)) {
          is DailyActiveUser -> {
            record.count = c
          }
          else -> {
            DailyActiveUser.new(today) {
              count = c
            }
          }
        }
      }
    }
  }
}

class DauCommand : CommandLineSubCommand, CliktCommand(name = "dau", help = "显示当日dau") {
  override fun run() {
    dbQuery {
      val contactCount = Contact.count()
      val userCount = User.count()
      val dau = DailyActiveUser.findById(currentDate())?.count
      echo("contact: $contactCount, user: $userCount, dau: $dau")
    }
  }
}
