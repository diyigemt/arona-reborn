package com.diyigemt.arona.magic

import com.diyigemt.arona.communication.event.TencentCallbackButtonEvent
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.magic.PluginMain.emit
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.core.CliktCommand
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private var Open = false

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.magic",
  name = "magic",
  author = "diyigemt",
  version = "0.0.1",
  description = "hello magic"
)) {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json
    }
  }
  suspend fun emit() {
    val text = httpClient.get(Config.url) {
      url {
        parameters.append("key", Config.key)
        parameters.append("qun", Config.target.toString())
      }
    }.bodyAsText()
    logger.info("get magic data: $text")
  }
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentCallbackButtonEvent> {
      if (Open) {
        Open = false
        logger.info("receive magic button")
        MessageChainBuilder(eventId = it.eventId).append("magic!").build().also { m ->
          it.contact.sendMessage(m)
        }
      }
    }
  }
}

@Suppress("unused")
class MagicConsoleCommand : CommandLineSubCommand, CliktCommand(
  name = "magic", help = "magic测试",
  invokeWithoutSubcommand = true
) {
  override fun run() {
    runBlocking {
      PluginMain.logger.info("requesting magic message")
      emit()
      Open = true
      PluginMain.logger.info("magic message received, sending response")
    }
  }
}

object Config : AutoSavePluginData("config") {
  val url by value("")
  val target by value(0L)
  val key by value("")
}
