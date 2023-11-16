package com.diyigemt.arona.webui.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun Application.configureSerialize() {
  install(ContentNegotiation) {
    json()
  }
}
