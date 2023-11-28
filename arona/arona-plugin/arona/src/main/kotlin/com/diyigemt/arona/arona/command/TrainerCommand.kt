package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.command.TrainerCommand.trainer
import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuerySuspended
import com.diyigemt.arona.arona.database.image.ImageCacheSchema
import com.diyigemt.arona.arona.database.image.update
import com.diyigemt.arona.arona.tools.BackendEndpoint
import com.diyigemt.arona.arona.tools.NetworkTool
import com.diyigemt.arona.arona.tools.ServerResponse
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextMessage
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.isGroupOrPrivate
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentGuildImage
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.github.ajalt.clikt.parameters.arguments.argument
import io.ktor.client.request.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ImageQueryData(
  val name: String,
  val hash: String,
  val content: String,
  val type: String,
)

@Suppress("unused")
object TrainerCommand : AbstractCommand(
  Arona,
  "攻略",
  description = "提供各种攻略"
) {
  private val arg by argument(name = "图片名", help = "学生名称/别名/主线地图/其他杂图名称")
  private val serializer = ServerResponse.serializer(ListSerializer(ImageQueryData.serializer()))
  private val json = Json { ignoreUnknownKeys = true }
  private suspend fun getImage(name: String): ServerResponse<List<ImageQueryData>> {
    return NetworkTool.request<List<ImageQueryData>>(BackendEndpoint.QueryImage) {
      parameter("name", name)
    }.getOrThrow()
  }

  private suspend fun CommandSender.sendImage(query: ImageQueryData) {
    if (isGroupOrPrivate()) {
      with(query) {
        dbQuerySuspended {
          ImageCacheSchema.findImage(name, hash)
            ?: subject.uploadImage("https://arona.cdn.diyigemt.com/image${content}").also {
              it.update(name, hash)
            }
        }.also {
          sendMessage(it)
        }
      }
    } else {
      MessageChainBuilder()
        .append(
          TencentGuildImage(
            url = "https://arona.cdn.diyigemt.com/image/s${query.content}"
          )
        ).build().also { im -> sendMessage(im) }
    }
  }

  suspend fun UserCommandSender.trainer() {
    getImage(arg).run {
      data?.run r1@{
        if (size != 1) {
          sendMessage("没有与${arg}对应的信息, 是否想要输入:\n${
            filterIndexed { index, _ -> index < 4 }
              .mapIndexed { index, it -> "${index + 1}. /攻略 ${it.name}" }
              .joinToString("\n")
          }")
          withTimeout(50000) {
            nextMessage ( filter =  filter@{ event ->
              val fb = event.message.filterIsInstance<PlainText>().firstOrNull() ?: return@filter false
              runCatching {
                fb.toString().toInt().let { this@r1.size >= it && it > 0 }
              }.getOrDefault(false)
            } ) {
              val feedback = it.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: "1"
              runCatching {
                feedback.toInt()
              }.onSuccess { i ->
                getImage(this@r1[i - 1].name).run {
                  data?.run {
                    sendImage(get(0))
                  }
                }
              }
            }
          }
        } else {
          sendImage(get(0))
        }
      } ?: sendMessage("空结果")
    }
  }
}
