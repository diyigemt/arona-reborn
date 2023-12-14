package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.config.MarkdownCompatiblyConfig
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.image.ImageCacheSchema.Companion.findImage
import com.diyigemt.arona.arona.database.image.contactType
import com.diyigemt.arona.arona.database.image.update
import com.diyigemt.arona.arona.tools.BackendEndpoint
import com.diyigemt.arona.arona.tools.NetworkTool
import com.diyigemt.arona.arona.tools.ServerResponse
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextMessage
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.isGroupOrPrivate
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.permission.UserDocument.Companion.readConfigOrDefault
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
    val from = contactType()
    if (isGroupOrPrivate()) {
      with(query) {
        val im = dbQuery {
          findImage(hash, from)
        }
        when (im) {
          is TencentImage -> {
            sendMessage(im)
          }

          else -> {
            subject.uploadImage("https://arona.cdn.diyigemt.com/image${content}").also {
              sendMessage(it)
            }.also {
              dbQuery { it.update(hash, from) }
            }
          }
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
        if (code != 200) {
          val mdConfig = userDocument().readConfigOrDefault(Arona, default = MarkdownCompatiblyConfig())
          if (mdConfig.enable) {
            val md = TencentMarkdown("102057194_1702305572") {
              append("search_target", arg)
              filterIndexed { index, _ -> index < 4 }.forEachIndexed { index, it ->
                append("option_${index + 1}", it.name)
              }
            }
            val btn = TencentKeyboard("102057194_1702305246")
            sendMessage(MessageChainBuilder().append(md).append(btn).build())
          } else {
            sendMessage("没有与${arg}对应的信息, 是否想要输入:\n${
              filterIndexed { index, _ -> index < 4 }
                .mapIndexed { index, it -> "${index + 1}. /攻略 ${it.name}" }
                .joinToString("\n")
            }")
          }
          withTimeout(50000) {
            nextMessage(filter = filter@{ event ->
              val fb = event.message.filterIsInstance<PlainText>().firstOrNull() ?: return@filter false
              runCatching {
                fb.toString().toInt().let { this@r1.size >= it && it > 0 }
              }.getOrDefault(false)
            }) {
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
