package com.diyigemt.arona.test

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.kts.host.evalFile
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.valueOrNull

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.test",
    name = "hello",
    author = "diyigemt",
    version = "0.0.15",
    description = "test interaction"
  )
) {
  override fun onLoad() {

  }
}

@Suppress("unused")
object TestCommand : AbstractCommand(
  PluginMain,
  "测试"
) {
  suspend fun UserCommandSender.test() {
    val result = evalFile(File("script/send.main.kts"))
    result.reports.forEach {
      println(": ${it.message}" + if (it.exception == null) "" else "${it.exception}")
    }
    when (val r = result.valueOrNull()?.returnValue) {
      is ResultValue.Value -> {
        if (r.value is MessageChainBuilder) {
          sendMessage((r.value as MessageChainBuilder).build())
        } else {
          bot.logger.info(r.value?.javaClass?.name)
        }
      }
      else -> {}
    }
  }
}
