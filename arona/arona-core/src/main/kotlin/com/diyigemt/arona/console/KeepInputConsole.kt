package com.diyigemt.arona.console

import kotlinx.coroutines.*
import org.fusesource.jansi.AnsiConsole
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder

suspend fun launchConsole() {
  AnsiConsole.systemInstall()
  val terminal = TerminalBuilder
    .builder()
    .jna(true)
    .jansi(true)
    .system(true)
    .build()
  val lineReader = LineReaderBuilder.builder().terminal(terminal).build()
  CoroutineScope(currentCoroutineContext() + SupervisorJob() + CoroutineName("console flush")).launch {
    // TODO remove
    return@launch
    while (true) {
      if (lineReader.isReading) {
        lineReader.callWidget(LineReader.CLEAR)
        lineReader.callWidget(LineReader.REDRAW_LINE)
        lineReader.callWidget(LineReader.REDISPLAY)
        lineReader.terminal.writer().flush()
      }
      delay(1000)
    }
  }
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

