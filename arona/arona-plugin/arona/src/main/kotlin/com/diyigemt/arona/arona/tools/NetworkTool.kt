package com.diyigemt.arona.arona.tools

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.Arona.logger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.logging.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


@Serializable
data class ServerResponse<T>(
  val status: Int,
  val message: String = "",
  val data: T?,
)

class HttpNotOkException(status: HttpStatusCode, body: String, traceId: String? = "") : Exception(
  "status: $status, traceId: $traceId, message: $body"
)

enum class BackendEndpoint(val path: String) {
  QueryImage("/api/v2/image"),
}

object NetworkTool {
  val json = Json { ignoreUnknownKeys = true }
  private const val BACKEND_BASE_PATH = "https://arona.diyigemt.com"

  suspend fun <T> request(
    endpoint: BackendEndpoint,
    decoder: KSerializer<T>,
    block: HttpRequestBuilder.() -> Unit,
  ): Result<ServerResponse<T>> {
    return runCatching {
      var bodyTmp = ""
      val resp = baseRequest(endpoint.path, block = block)
      if (resp.status == HttpStatusCode.OK) {
        bodyTmp = resp.bodyAsText()
        json.decodeFromString(ServerResponse.serializer(decoder), bodyTmp)
      } else {
        throw HttpNotOkException(resp.status, bodyTmp)
      }
    }.onFailure {
      when (it) {
        is HttpNotOkException -> {
          logger.error(
            "call endpoint failed, endpoint: {}, body: {}",
            endpoint, it.message
          )
        }

        else -> logger.error(it)
      }
    }
  }

  suspend inline fun <reified T> request(
    endpoint: BackendEndpoint,
    noinline block: HttpRequestBuilder.() -> Unit = {},
  ): Result<ServerResponse<T>> {
    return runCatching {
      var bodyTmp = ""
      val resp = baseRequest(endpoint.path, block = block)
      if (resp.status == HttpStatusCode.OK) {
        bodyTmp = resp.bodyAsText()
        json.decodeFromString<ServerResponse<T>>(bodyTmp)
      } else {
        throw HttpNotOkException(resp.status, bodyTmp)
      }
    }.onFailure {
      when (it) {
        is HttpNotOkException -> {
          logger.error(
            "call endpoint failed, endpoint: {}, body: {}",
            endpoint, it.message
          )
        }

        else -> logger.error(it)
      }
    }
  }

  suspend fun baseRequest(
    url: String,
    source: String = BACKEND_BASE_PATH,
    block: HttpRequestBuilder.() -> Unit = {},
  ) = Arona.httpClient.request {
    headers {
      append("Authorization", "")
      append("Version", "")
      append("Token", "")
    }
    contentType(ContentType.Application.Json)
    url("$source$url")
    block.invoke(this)
  }

}

