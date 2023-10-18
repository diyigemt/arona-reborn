package com.diyigemt.arona.commandline

import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.commandLineLogger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import io.ktor.util.logging.*
import kotlin.system.exitProcess

annotation class CommandLineSubCommand

class CommandMain : CliktCommand(name = "cli", printHelpOnEmptyArgs = true, invokeWithoutSubcommand = true) {
  @Suppress("UNCHECKED_CAST")
  companion object {
    private val instance = CommandMain().subcommands(ReflectionUtil
      .scanTypeAnnotatedClass(CommandLineSubCommand::class)
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

@CommandLineSubCommand
class ExitCommand : CliktCommand(name = "exit", help = "安全退出程序") {
  override fun run() {
    waitInput("confirm exit? (y/N): ")
      .onSuccess {
        echo("exiting")
        exitProcess(0)
      }.onFailure {
        echo("cancel")
      }
  }
}

internal fun waitInput(tip: String?): Result<String> {
  if (tip != null) {
    print(tip)
  }
  val result = readlnOrNull()
  return if (result == null) {
    Result.failure(Exception(""))
  } else Result.success(result)
}
