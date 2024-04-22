package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BuildInSuperAdminCommandOwner
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.h1
import com.diyigemt.arona.communication.message.indexedList
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.user.recorder.database.*
import com.diyigemt.arona.user.recorder.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.user.recorder",
    name = "user-recorder",
    author = "diyigemt",
    version = "1.2.5",
    description = "record user data"
  )
) {
  private lateinit var dauJob: Job
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentMessageEvent> {
      // 统计消息数
      val today = currentDate()
      val currentDateTime = currentDateTime()
      val dauRecord = dbQuery {
        when (val record = DailyActiveUser.findById(today)) {
          is DailyActiveUser -> {
            record.message += 1
            record
          }

          else -> {
            DailyActiveUser.new(today) {
              message = 1
            }
          }
        }
      }
      // 统计contact
      dbQuery {
        when (val contact = Contact.find { ContactTable.id eq it.subject.id }.firstOrNull()) {
          is Contact -> {
            contact.lastActive = currentDateTime
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
      // 统计user
      dbQuery {
        when (val user = User.find { UserTable.id eq sender.id }.firstOrNull()) {
          is User -> {
            user.actionCount
            user.lastActive = currentDateTime
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
        CommandManager.matchCommandName(commandStr.replace("/", "")) ?: return@subscribeAlways
      // 统计command
      dbQuery {
        // 累计command
        when (val fCommand = Command.find { CommandTable.name eq command }.firstOrNull()) {
          is Command -> fCommand.count++
          else -> {
            Command.new {
              name = command
              count = 1
            }
          }
        }
        // 日command
        dbQuery {
          if (command in dauRecord.commands) {
            val tmp = dauRecord.commands.toMutableMap()
            tmp[command] = tmp[command]!! + 1
            dauRecord.commands = tmp
          } else {
            val tmp = dauRecord.commands.toMutableMap()
            tmp[command] = 1
            dauRecord.commands = tmp
          }
        }
      }
    }
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> {
      when (val subject = it.subject) {
        is com.diyigemt.arona.communication.contact.User -> {
          dbQuery {
            when (User.find { UserTable.id eq subject.id }.firstOrNull()) {
              is User -> {}
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
            contact.active = when (it) {
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
    dauJob = launch(SupervisorJob()) {
      while (true) {
        dbQuery {
          val today = currentDate()
          val u = User.find(UserTable.lastActive greater today).count().toInt()
          val c = Contact.find(ContactTable.lastActive greater today).count().toInt()
          when (val record = DailyActiveUser.findById(today)) {
            is DailyActiveUser -> {
              record.count = u
              record.contact = c
            }

            else -> {
              DailyActiveUser.new(today) {
                count = u
                contact = c
              }
            }
          }
        }
        delay(60 * 1000L)
      }
    }
  }
}

@Suppress("unused")
class DauClCommand : CommandLineSubCommand, CliktCommand(name = "dau", help = "显示当日dau") {
  private val offset by argument().int().default(0)
  override fun run() {
    // dau
    val date = now().minus(DateTimePeriod(days = offset), TimeZone.currentSystemDefault()).toDate()
    val dau = dbQuery {
      val contactCount = Contact.count()
      val userCount = User.count()
      echo("date: $date")
      echo("contact: $contactCount, user: $userCount")
      DailyActiveUser.findById(date)!!
    }
    echo(dau)
    dau.commands.entries.sortedBy { it.value }.forEach {
      echo("${it.key}: ${it.value}")
    }
    // 指令执行次数
    dbQuery {
      Command.all()
        .sortedBy { it.count }
        .forEach {
          echo("${it.name}: ${it.count}")
        }
    }
  }
}

@Suppress("unused")
class DauCommand : AbstractCommand(
  BuildInSuperAdminCommandOwner,
  "dau",
  description = "展示当日dau",
) {
  private val offset by argument().int().default(0)

  suspend fun UserCommandSender.dau() {
    val date = now()
      .minus(DateTimePeriod(days = offset), TimeZone.currentSystemDefault())
      .toDate()
    val md = tencentCustomMarkdown {
      h1(date)
    }
    val dau = dbQuery {
      val contactCount = Contact.count()
      val userCount = User.count()
      md append tencentCustomMarkdown {
        +"total contact: $contactCount, total user: $userCount"
      }
      DailyActiveUser.findById(date)!!
    }
    md append tencentCustomMarkdown {
      +"dau: ${dau.count}"
      +"contact: ${dau.contact}"
      +"message: ${dau.message}"
      +"commands:"
      indexedList {
        dau.commands.entries.sortedBy { it.value }.forEach {
          +"${it.key}: ${it.value}"
        }
      }
    }
    sendMessage(md)
  }
}
