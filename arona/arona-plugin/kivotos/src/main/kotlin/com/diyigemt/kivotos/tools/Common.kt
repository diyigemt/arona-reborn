package com.diyigemt.kivotos.tools

import com.diyigemt.arona.arona.command.ImageQueryData
import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.utils.ServerResponse
import com.diyigemt.kivotos.coffee.CoffeeInviteCommand
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

private val json = Json {
  ignoreUnknownKeys = true
}
private val httpClient = HttpClient(CIO) {
  install(ContentNegotiation) {
    json
  }
}

suspend fun normalizeStudentName(name: String): String? {
  return DatabaseProvider.dbQuery {
    StudentSchema.find {
      StudentTable.name eq name
    }.firstOrNull()
  }?.name ?: // 没找到 尝试在远端找
  httpClient.get("https://arona.diyigemt.com/api/v2/image") {
    parameter("name", name)
  }.let {
    return@let kotlin.runCatching {
      if (it.status == HttpStatusCode.OK) {
        val tmp = it.bodyAsText().let {
          json.decodeFromString<ServerResponse<List<ImageQueryData>>>(it)
        }
        return@runCatching if (tmp.code == HttpStatusCode.OK) {
          tmp.data?.firstOrNull()?.content?.substringAfterLast("/")?.substringBefore(".")
        } else {
          null
        }
      } else {
        null
      }
    }.getOrNull()
  }
}