package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.error
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureErrorHandler() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      commandLineLogger.error(cause)
      call.respondText(text = "500: ${cause.message}", status = HttpStatusCode.InternalServerError)
    }
  }
}
