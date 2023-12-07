package com.diyigemt.arona.console

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import kotlinx.coroutines.delay
import org.fusesource.jansi.AnsiConsole
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder

val lineReader: LineReader by lazy {
  AnsiConsole.systemInstall()
  val terminal = TerminalBuilder
    .builder()
    .jna(true)
    .jansi(true)
    .system(true)
    .build()
  LineReaderBuilder.builder().terminal(terminal).build()
}

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
  private var pattern: String = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

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
