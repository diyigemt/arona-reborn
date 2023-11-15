package com.diyigemt.arona

import com.diyigemt.arona.commandline.CommandMain
import com.diyigemt.arona.communication.TencentBotClient
import com.diyigemt.arona.communication.event.Event
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.plugins.PluginManager
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.webui.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object AronaApplication : CoroutineScope {
  fun run() {
    PluginManager.loadPluginFromPluginDirectory()
    PluginManager.initPlugin()
    TencentBotClient.invoke(aronaConfig.bot).auth()
    CoroutineScope(Dispatchers.IO).launch {
      while (true) {
        val input = readlnOrNull() ?: ""
        CommandMain.run(input.split(" "))
        delay(1000)
      }
    }
    val environment = applicationEngineEnvironment {
      connector {
        port = 8081
        host = "0.0.0.0"
      }
      rootPath = "/api/v2"
      module(Application::module)
    }
    embeddedServer(Netty, environment).start(wait = true)
  }

  override val coroutineContext: CoroutineContext = EmptyCoroutineContext
}

fun main() {
  AronaApplication.run()
}

fun Application.module() {
  configureHTTP()
  configureRouting()
  configureSerialize()
  configureDoubleReceive()
//  configureErrorHandler()
}
