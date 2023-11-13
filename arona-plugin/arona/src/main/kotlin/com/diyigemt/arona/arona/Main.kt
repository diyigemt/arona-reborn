package com.diyigemt.arona.arona

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.GuildChannelCommandSender
import com.diyigemt.arona.communication.event.TencentGuildMessageEvent
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object Arona : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.arona",
  name = "arona",
  author = "diyigemt",
  version = "2.3.3",
  description = "hello world"
)) {
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
  val data: T?
)
@Serializable
data class ImageQueryData(
  val name: String,
  val hash: String,
  val content: String,
  val type: String
)

object TrainerCommand : AbstractCommand(
  Arona,
  "攻略",
  description = "提供各种攻略"
) {
  private val arg by argument("学生名称/别名/主线地图/其他杂图名称")
  suspend fun GuildChannelCommandSender.trainer() {
    val resp = Arona.httpClient.get("http://127.0.0.1:8080/api/v2/image?name=$arg")
    val text = resp.bodyAsText()
    Arona.logger.info(text)
    val body = Json.decodeFromString(ServerResponse.serializer(ListSerializer(ImageQueryData.serializer())), text)
    if (body.data == null) {
      sendMessage("空结果")
      return
    }
    val message = MessageChainBuilder()
      .append(
        TencentImage(
          url = "https://arona.cdn.diyigemt.com/image${body.data.first().content}"
        )
      ).build()
    sendMessage(message)
  }
}
