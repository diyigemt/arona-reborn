package com.diyigemt.arona.webui.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*

val HttpHeaders.AronaInstanceVersion: String
  get() = "Version"

val HttpHeaders.AronaAdminToken: String
  get() = "Token"

val HttpHeaders.XRealIp: String
  get() = "X-Real-IP"

fun Application.configureHTTP() {
  install(ForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
  install(XForwardedHeaders) // WARNING: for security, do not include this if not behind a reverse proxy
  install(CORS) {
    allowMethod(HttpMethod.Options)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Delete)
    allowHeader(HttpHeaders.Authorization)
    allowHeader(HttpHeaders.AronaInstanceVersion)
    allowHeader(HttpHeaders.AronaAdminToken)
    allowHeader(HttpHeaders.XRealIp)
    anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
  }
}
