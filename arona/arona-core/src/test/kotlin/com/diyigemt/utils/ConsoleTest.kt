package com.diyigemt.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fusesource.jansi.AnsiConsole
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import kotlin.test.Test

class ConsoleTest {

  @Test
  fun testJLine() {
    AnsiConsole.systemInstall()
    val terminal = TerminalBuilder
      .builder()
      .jna(true)
      .jansi(true)
      .system(true)
      .build()
    val lineReader = LineReaderBuilder.builder().terminal(terminal).build()
    CoroutineScope(Dispatchers.IO).launch {
      while (true) {
        lineReader.callWidget(LineReader.CLEAR)
        println("test")
        lineReader.callWidget(LineReader.REDRAW_LINE)
        lineReader.callWidget(LineReader.REDISPLAY)
        lineReader.terminal.writer().flush()
        delay(2000)
      }
    }
    while (true) {
      runCatching {
        println(lineReader.readLine("root> "))
      }.onFailure {
        return
      }
    }
  }

}
