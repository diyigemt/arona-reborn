package com.diyigemt.arona.test.pressure

import com.diyigemt.arona.communication.event.mockGroupMessage
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.test.pressure",
    name = "hello",
    author = "diyigemt",
    version = "0.1.0",
    description = "test pressure"
  )
) {
  override fun onLoad() {

  }
}

@Suppress("unused")
class PressureTestCommandCommand : CommandLineSubCommand, CliktCommand(name = "pressure", help = "压测") {
  private val count by argument("threads").int().default(100)
  private val command by argument("command").default("塔罗牌")
  override fun run() {
    (0 .. count).map {
      PluginMain.launch {
        val m = it.toString()
        mockGroupMessage(m, m, command)
      }
    }.forEach {
      runBlocking {
        it.join()
      }
    }
    runBlocking {
      val m = (count + 1).toString()
      mockGroupMessage(m, m, command)
    }
  }
}
