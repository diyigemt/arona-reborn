package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.config.BaseConfig
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.image.ImageCacheSchema.Companion.findImage
import com.diyigemt.arona.arona.database.image.contactType
import com.diyigemt.arona.arona.database.image.update
import com.diyigemt.arona.arona.tools.BackendEndpoint
import com.diyigemt.arona.arona.tools.NetworkTool
import com.diyigemt.arona.arona.tools.ServerResponse
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextMessage
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.isGuild
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.github.ajalt.clikt.parameters.arguments.argument
import io.ktor.client.request.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
data class ImageQueryData(
  val name: String,
  val hash: String,
  val content: String,
  val type: String,
)

@Serializable
data class TrainerConfig(
  val override: List<TrainerOverrideConfig> = listOf(),
) : PluginWebuiConfig() {
  override fun check() {}
}

object TrainerOverrideTypeSerializer : KSerializer<TrainerOverrideType> {
  // TODO
  override fun deserialize(decoder: Decoder): TrainerOverrideType {
    decoder.decodeString()
    return TrainerOverrideType.RAW
  }

  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TrainerOverrideType", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: TrainerOverrideType) = encoder.encodeString(value.name)
}

@Serializable
data class TrainerOverrideConfig(
  val type: TrainerOverrideType,
  val name: List<String>,
  val value: String,
)

@Serializable(with = TrainerOverrideTypeSerializer::class)
enum class TrainerOverrideType {
  RAW
}

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

  private suspend fun UserCommandSender.sendImage(query: ImageQueryData) {
    val from = contactType()
    with(query) {
      val url = "https://arona.cdn.diyigemt.com/image" +
          (if (isGuild()) "/s" else "") +
          content
      val im = dbQuery {
        findImage(hash, from)
      }
      when (im) {
        is TencentImage -> {
          sendMessage(im).also {
            if (it.isFailed) {
              subject.uploadImage(url).also { image ->
                sendMessage(image)
                dbQuery { image.update(hash, from) }
              }
            }
          }
        }

        else -> {
          subject.uploadImage(url).also {
            sendMessage(it)
            dbQuery { it.update(hash, from) }
          }
        }
      }

    }
  }

  suspend fun UserCommandSender.trainer() {
    val override = readPluginConfigOrDefault(Arona, default = TrainerConfig()).override
    val match = override.firstOrNull { it.name.contains(arg) }?.value ?: arg
    getImage(match).run {
      data?.run r1@{
        if (code != 200) {
          val mdConfig = readUserPluginConfigOrDefault(Arona, default = BaseConfig()).markdown
          if (mdConfig.enable) {
            val md = TencentTemplateMarkdown("102057194_1702305572") {
              append("search_target", match)
              filterIndexed { index, _ -> index < 4 }.forEachIndexed { index, it ->
                append("option_${index + 1}", it.name)
              }
            }
            val btn = TencentTempleKeyboard("102057194_1702611887")
            sendMessage(MessageChainBuilder().append(md).append(btn).build())
          } else {
            sendMessage("没有与${match}对应的信息, 是否想要输入:\n${
              filterIndexed { index, _ -> index < 4 }
                .mapIndexed { index, it -> "${index + 1}. /攻略 ${it.name}" }
                .joinToString("\n")
            }")
          }
          val message = nextMessage(50000) { event ->
            val fb = event.message.filterIsInstance<PlainText>().firstOrNull() ?: return@nextMessage false
            runCatching {
              fb.toString().toInt().let { this@r1.size >= it && it > 0 }
            }.getOrDefault(false)
          }
          val feedback = message.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: "1"
          runCatching {
            feedback.toInt()
          }.onSuccess { i ->
            getImage(this@r1[i - 1].name).run {
              data?.run {
                sendImage(get(0))
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
