package com.diyigemt.arona.console

import com.diyigemt.arona.command.ExecutorMap
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.runSuspend
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.terminal.PrintRequest
import com.github.ajalt.mordant.terminal.StandardTerminalInterface
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.util.logging.*
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

interface CommandLineSubCommand

// Mordant 3: Terminal 主构造私有, 改用静态工厂 ansiLevel/.../terminalInterface 配置;
// 这里走 forTerminalInterface 把自定义 JLineTerminalInterface 注入.
internal val commandTerminal = Terminal(terminalInterface = JLineTerminalInterface())

// Clikt 5: CliktCommand 构造器只剩 name; help/epilog/invokeWithoutSubcommand/printHelpOnEmptyArgs
// 等改 override 属性. 项目现有命令仅通过 help= 和这两个布尔传入, 用一个轻量基类把 override 集中管理,
// 避免每个子类各自 override. CommandLineSubCommand 仍是空 marker interface.
abstract class ConsoleSubCommand(
  name: String,
  private val helpText: String = "",
) : CliktCommand(name = name) {
  override fun help(context: Context): String = helpText
  override val invokeWithoutSubcommand: Boolean = true
}

class CommandMain : CliktCommand(name = "cli") {
  override fun help(context: Context): String = ""
  override val printHelpOnEmptyArgs: Boolean = true
  override val invokeWithoutSubcommand: Boolean = true

  @Suppress("UNCHECKED_CAST")
  companion object {
    private val instance = CommandMain().subcommands(ReflectionUtil
      .scanInterfacePetClass(CommandLineSubCommand::class)
      .map { Class.forName(it) }
      .filter { CliktCommand::class.java.isAssignableFrom(it) }
      .map { it.getDeclaredConstructor().newInstance() }
        as List<CliktCommand>).apply {
      context {
        terminal = commandTerminal
      }
    }
    internal fun registerCommands(command: List<CliktCommand>) {
      command.forEach {
        it.context {
          terminal = commandTerminal
        }
      }
      instance.subcommands(command)
    }
    internal fun registeredCommands() = instance.registeredSubcommands()
    internal fun run(argv: List<String>) {
      runCatching {
        instance.parse(argv)
      }.onFailure {
        when {
          it is CliktError -> instance.echo(instance.getFormattedHelp(it))
          else -> commandLineLogger.error(it)
        }
      }
    }
  }

  override fun run() {}
}

@Suppress("unused")
class ExitCommand : CommandLineSubCommand, ConsoleSubCommand(name = "exit", helpText = "安全退出程序") {
  override fun run() {
    echo("exiting")
    BotManager.close()
    exitProcess(0)
  }
}

@Suppress("unused")
class PermissionManagerCommand : CommandLineSubCommand, ConsoleSubCommand(name = "perm", helpText = "查看权限") {
  override fun run() {
    echo("permission")
  }
}

@Suppress("unused")
class GlobalAnnouncementCommand : CommandLineSubCommand, ConsoleSubCommand(name = "anno", helpText = "主动消息通知") {
  private val message by argument(name = "contain")
  override fun run() {
    runSuspend {
      val contacts = ContactDocument.contacts()
      val guilds = ContactDocument.guilds()
      contacts.forEachIndexed { index, c ->
        delay(260L)
        BotManager.getBot().groups.getOrCreate(c.id).sendMessage(message)
        echo("send group($index/${contacts.size}): ${c.contactName}")
      }
      guilds.forEachIndexed { index, g ->
        delay(260L)
        BotManager.getBot().guilds.getOrCreate(g.id).sendMessage(message)
        echo("send guild($index/${guilds.size}): ${g.contactName}")
      }
    }
  }
}

@Suppress("unused")
class MonitorCommand : CommandLineSubCommand, ConsoleSubCommand(name = "monitor", helpText = "系统资源监视") {
  override fun run() {
    ExecutorMap
      .entries
      .filter {
        it.value.runningCounter.value > 0
      }
      .sortedBy { it.key.length }
      .forEach {
      echo("${it.key} -> ${it.value.runningCounter.value}-${it.value.idleWorkers}/${it.value.pendingTasks}")
    }
  }
}

// Mordant 3 引入 StandardTerminalInterface 抽象基类, 已实现 info / getTerminalSize /
// readInputEvent / enterRawMode / shouldAutoUpdateSize 等新增 abstract 方法的默认行为;
// 自定义只覆盖 completePrintRequest (打印重定向到 JLine) 与 readLineOrNull (读 JLine 一行).
class JLineTerminalInterface : StandardTerminalInterface() {
  override fun completePrintRequest(request: PrintRequest) {
    when {
      request.stderr -> {
        appendConsole(request.text)
        if (request.trailingLinebreak) {
          appendConsole("")
        }
      }

      request.trailingLinebreak -> {
        if (request.text.isEmpty()) {
          appendConsole()
        } else {
          appendConsole(request.text)
        }
      }

      else -> appendConsole(request.text)
    }
  }

  override fun readLineOrNull(hideInput: Boolean): String? = lineReader.readLine(">")
}
