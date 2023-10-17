package com.diyigemt.arona.commandline

import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.commandLineLogger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import io.ktor.util.logging.*
import kotlin.system.exitProcess

annotation class SubCommand

class CommandMain : CliktCommand(name = "cli", printHelpOnEmptyArgs = true, invokeWithoutSubcommand = true) {
  @Suppress("UNCHECKED_CAST")
  companion object {
    private val instance = CommandMain().subcommands(ReflectionUtil
      .scanTypeAnnotatedClass(SubCommand::class)
      .map { Class.forName(it) }
      .map { it.getDeclaredConstructor().newInstance() }
        as List<CliktCommand>)
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

@SubCommand
class ExitCommand : CliktCommand(name = "exit", help = "安全退出程序") {
  override fun run() {
    echo("exiting")
    exitProcess(0)
  }
}

