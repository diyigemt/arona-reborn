package com.diyigemt.arona.webui.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.*

fun Application.configureMonitoring() {
  install(CallLogging) {
    level = Level.INFO
    format { call ->
      "${call.request.httpMethod.value} ${call.request.uri}"
    }
  }
}
