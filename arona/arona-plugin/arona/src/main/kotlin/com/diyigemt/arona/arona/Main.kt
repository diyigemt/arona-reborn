package com.diyigemt.arona.arona

import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

object Arona : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona",
    name = "arona",
    author = "diyigemt",
    version = "0.1.17",
    description = "hello world"
  )
) {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json
    }
  }

  override fun onLoad() {

  }
}
