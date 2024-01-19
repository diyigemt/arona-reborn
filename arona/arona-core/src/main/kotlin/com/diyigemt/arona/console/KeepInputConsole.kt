package com.diyigemt.arona.console

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import com.github.ajalt.mordant.terminal.ConversionResult
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.delay
import org.fusesource.jansi.AnsiConsole
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString

internal val lineReader: LineReader by lazy {
  AnsiConsole.systemInstall()
  val terminal = TerminalBuilder
    .builder()
    .jna(true)
    .jansi(true)
    .system(true)
    .build()
  LineReaderBuilder.builder().terminal(terminal).completer { _, _, candidates ->
    candidates.addAll(
      CommandMain.registeredCommands().map {
        val name = it.commandName
        Candidate(AttributedString.stripAnsi(name), name, null, null, null, null, true)
      }
    )
  }.build()
}

fun Terminal.confirm(
  prompt: String,
  default: String = "Y",
  showDefault: Boolean = true,
  showChoices: Boolean = true,
  hideInput: Boolean = false,
  promptSuffix: String = ": ",
  invalidChoiceMessage: String = "Invalid value, choose from ",
) = prompt(
  prompt, default == "Y", showDefault, showChoices, hideInput, listOf(true, false), promptSuffix,
  invalidChoiceMessage
) {
  return@prompt if (it in listOf("Y", "N")) {
    ConversionResult.Valid(it == "Y")
  } else {
    ConversionResult.Invalid("Y or N")
  }
} as Boolean

suspend fun launchConsole() {
  while (true) {
    runCatching {
      val input = lineReader.readLine("> ")
      CommandMain.run(input.split(" "))
      delay(1000)
    }.onFailure {
      return
    }
  }
}

fun appendConsole(message: String? = null) {
  lineReader.printAbove(message)
}

class CustomAppender : AppenderBase<ILoggingEvent>() {
  private val encoder: LayoutWrappingEncoder<ILoggingEvent> = LayoutWrappingEncoder()
  private val layout: PatternLayout = PatternLayout()
  private var pattern: String = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %highlight(%-5level) %logger{72} - %msg%n"

  override fun start() {
    super.start()
    encoder.context = context
    encoder.layout = layout
    layout.context = context
    layout.pattern = pattern
    layout.start()
    encoder.start()
  }

  override fun stop() {
    encoder.stop()
    super.stop()
  }

  override fun append(event: ILoggingEvent) {
    lineReader.printAbove(layout.doLayout(event))
  }
}
