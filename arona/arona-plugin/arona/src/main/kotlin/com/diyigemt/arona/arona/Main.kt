package com.diyigemt.arona.arona

import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.image.ImageTableModel
import com.diyigemt.arona.arona.database.image.ResourceType
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.GuildChannelCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.parameters.arguments.argument
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object Arona : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona",
    name = "arona",
    author = "diyigemt",
    version = "2.3.3",
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

@Serializable
data class ServerResponse<T>(
  val code: Int,
  val message: String,
  val data: T?,
)

@Serializable
data class ImageQueryData(
  val name: String,
  val hash: String,
  val content: String,
  val type: String,
)

object TrainerCommand : AbstractCommand(
  Arona,
  "攻略",
  description = "提供各种攻略"
) {
  private val arg by argument("学生名称/别名/主线地图/其他杂图名称")
  private val serializer = ServerResponse.serializer(ListSerializer(ImageQueryData.serializer()))
  suspend fun UserCommandSender.trainer() {
    Arona.httpClient.get("http://127.0.0.1:8080/api/v2/image") {
      parameter("name", arg)
    }.run {
      Json.decodeFromString(serializer, bodyAsText())
    }.run {
      data?.run {
        dbQuery {
          ImageTableModel.new {
            name = first().name
            hash = first().hash
            content = first().content
            type = ResourceType.fromValue(first().type)
          }
        }
        MessageChainBuilder()
          .append(
            TencentImage(
              url = "https://arona.cdn.diyigemt.com/image${first().content}"
            )
          ).build().also { sendMessage(it) }
      } ?: sendMessage("空结果")
    }
//    sendMessage("查询中")
//    delay(1000)
//    sendMessage("查询成功")
  }
}
