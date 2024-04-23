package com.diyigemt.arona.console

import com.diyigemt.arona.command.ExecutorMap
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.runSuspend
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.terminal.*
import io.ktor.util.logging.*
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

interface CommandLineSubCommand

internal val commandTerminal = Terminal(JLineTerminalInterface())

class CommandMain : CliktCommand(name = "cli", printHelpOnEmptyArgs = true, invokeWithoutSubcommand = true) {
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
class ExitCommand : CommandLineSubCommand, CliktCommand(name = "exit", help = "安全退出程序") {
  override fun run() {
    echo("exiting")
    BotManager.close()
    exitProcess(0)
  }
}

@Suppress("unused")
class PermissionManagerCommand : CommandLineSubCommand, CliktCommand(name = "perm", help = "查看权限") {
  override fun run() {
    echo("permission")
  }
}

@Suppress("unused")
class GlobalAnnouncementCommand : CommandLineSubCommand, CliktCommand(name = "anno", help = "主动消息通知") {
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
class MonitorCommand : CommandLineSubCommand, CliktCommand(name = "monitor", help = "系统资源监视") {
  override fun run() {
    val rt = Runtime.getRuntime()
    val max = rt.maxMemory() / 1024 / 8
    val usage = (rt.totalMemory() - rt.freeMemory()) / 1024 / 8
    val free = rt.freeMemory() / 1024 / 8
    echo("max: $max MB, usage: $usage MB, free: $free MB")
    ExecutorMap.entries.sortedBy { it.key.length }.forEach {
      echo("${it.key} -> ${it.value.extensionCounter.value}-${it.value.poolSize}/${it.value.capacity}")
    }
  }
}

class JLineTerminalInterface : TerminalInterface {
  override val info: TerminalInfo = TerminalDetection.detectTerminal(null, null, null, null, null)

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
