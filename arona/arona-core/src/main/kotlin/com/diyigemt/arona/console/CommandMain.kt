package com.diyigemt.arona.console

import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.commandLineLogger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.mordant.terminal.*
import io.ktor.util.logging.*
import kotlin.system.exitProcess

@Target(AnnotationTarget.CLASS)
annotation class CommandLineSubCommand

internal val commandTerminal = Terminal(JLineTerminalInterface())

class CommandMain : CliktCommand(name = "cli", printHelpOnEmptyArgs = true, invokeWithoutSubcommand = true) {
  @Suppress("UNCHECKED_CAST")
  companion object {
    private val instance = CommandMain().subcommands(ReflectionUtil
      .scanTypeAnnotatedClass(CommandLineSubCommand::class)
      .map { Class.forName(it) }
      .map { it.getDeclaredConstructor().newInstance() }
        as List<CliktCommand>).apply {
      context {
        terminal = commandTerminal
      }
    }

    fun run(argv: List<String>) {
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

@CommandLineSubCommand
class ExitCommand : CliktCommand(name = "exit", help = "安全退出程序") {
  override fun run() {
    echo("exiting")
    exitProcess(0)
  }
}

@CommandLineSubCommand
class PermissionManagerCommand : CliktCommand(name = "perm", help = "查看权限") {
  override fun run() {
    echo("permission")
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
