package com.diyigemt.arona

import com.diyigemt.arona.commandline.CommandMain
import com.diyigemt.arona.plugins.PluginManager
import com.diyigemt.arona.webui.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*

fun main() {
  CoroutineScope(Dispatchers.IO).launch {
    while (true) {
      val input = readlnOrNull() ?: ""
      CommandMain.run(input.split(" "))
      delay(1000)
    }
  }
  val environment = applicationEngineEnvironment {
    connector {
      port = 8080
      host = "0.0.0.0"
    }
    rootPath = "/api/v2"
    module(Application::module)
  }
  embeddedServer(Netty, environment).start(wait = true)
}

fun Application.module() {
  configureHTTP()
  configureRouting()
  configureSerialize()
  configureDoubleReceive()
  PluginManager.loadPluginFromPluginDirectory()
  PluginManager.initPlugin()
//  configureErrorHandler()
}
