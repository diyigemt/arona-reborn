package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BuildInSuperAdminCommandOwner
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.command.UnderDevelopment
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

// dau.{date}.dau hash_map uid -> messageCount 日活
// dau.{date}.contact hash_map cid -> messageCount 日环境
// dau.{date}.message integer 消息数
// dau.{date}.command hash_map commandName -> integer 指令执行
// dau.command hash_map commandName -> integer 总指令执行
// dau.user hash_map uid -> string 最后交互时间
// dau.contact hash_map cid -> string 最后交互时间

fun dayDauKey(date: String) = "dau.$date.dau"
fun dayContactKey(date: String) = "dau.$date.contact"
fun dayMessageKey(date: String) = "dau.$date.message"
fun dayCommandKey(date: String) = "dau.$date.command"
const val UserKey = "dau.user"
const val ContactKey = "dau.contact"
const val CommandKey = "dau.command"

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.user.recorder",
    name = "user-recorder",
    author = "diyigemt",
    version = "1.2.12",
    description = "record user data"
  )
) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentMessageEvent> { ev ->
      PluginMain.launch {
        // 统计消息数
        val today = currentDate()
        val currentDateTime = currentDateTime()
        val dauKey = dayDauKey(today)
        val contactKey = dayContactKey(today)
        val messageKey = dayMessageKey(today)
        val messageString =
          ev.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return@launch
        val commandStr =
          messageString.split(" ").toMutableList().removeFirstOrNull() ?: return@launch
        val command =
          CommandManager.matchCommandName(commandStr.replace("/", "")) ?: return@launch
        val dayCommandKey = dayCommandKey(today)
        redis {
          with(pipelined()) {
            incr(messageKey)
            hincrBy(dayCommandKey, command, 1L)
            hincrBy(CommandKey, command, 1L)
            hincrBy(dauKey, ev.sender.id, 1L)
            hincrBy(contactKey, ev.subject.id, 1L)
            hset(UserKey, ev.sender.id to currentDateTime)
            hset(ContactKey, ev.subject.id to currentDateTime)
            execute()
          }
        }
      }
    }
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> { ev ->
      PluginMain.launch {
        val currentDateTime = currentDateTime()
        redis {
          with(pipelined()) {
            hset(UserKey, ev.user.id to currentDateTime)
            hset(ContactKey, ev.subject.id to currentDateTime)
            execute()
          }
        }
      }
    }
  }
}

suspend fun getDauString(date: String): TencentCustomMarkdown {
  var md = tencentCustomMarkdown {
    h1(date)
  }
  val userCount = redis {
    hlen(UserKey)
  }
  val contactCount = redis {
    hlen(ContactKey)
  }
  md += "contact: $contactCount, user: $userCount"
  val dayCommandKey = dayCommandKey(date)
  // commandName -> count
  val (dayCommandExecute, commandExecute) =
    redis {
      hgetAll(dayCommandKey) to hgetAll(CommandKey)
    }
  .let {
    it.first
      .windowed(2, 2, true)
      .associate { w -> w[0] to w[1] } to it.second
      .windowed(2, 2, true)
      .associate { w -> w[0] to w[1] }
  }
  val dauKey = dayDauKey(date)
  val contactKey = dayContactKey(date)
  val messageKey = dayMessageKey(date)
  redis {
    md += "user=${hlen(dauKey)}, contact=${hlen(contactKey)}, message=${get(messageKey)}"
  }
  // 指令执行次数
  md append tencentCustomMarkdown {
    indexedList {
      commandExecute.forEach {
        +"${it.key}: ${dayCommandExecute[it.key] ?: 0}/${it.value}"
      }
    }
  }
  return md
}

@Suppress("unused")
class DauClCommand : CommandLineSubCommand, CliktCommand(name = "dau", help = "显示当日dau") {
  private val offset by argument().int().default(0)
  override fun run() {
    // dau
    val date = now().minus(DateTimePeriod(days = offset), TimeZone.currentSystemDefault()).toDate()
    runBlocking {
      getDauString(date)
    }
      .content
      .split("\n")
      .filter { it.isNotBlank() }
      .forEach {
        echo(it)
      }
  }
}

@Suppress("unused")
@UnderDevelopment
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
    val md = getDauString(date)
    sendMessage(md)
  }
}
