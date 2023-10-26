package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.event.TencentMessageEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
internal class TencentMessageIntentsBuilder {
  private val offsets = mutableListOf<TencentMessageIntentSuperType>()
  fun append(intent: TencentMessageIntentSuperType) = this.apply {
    offsets.add(intent)
  }
  fun append(intents: Array<TencentMessageIntentSuperType>) = this.apply {
    offsets.addAll(intents)
  }
  fun build() = offsets
    .map { it.offset }
    .toMutableList()
    .apply { this[0] = 1 shl this[0] }
    .reduce { acc, i -> acc or ( 1 shl i ) }
  fun buildAll() = this.apply {
    append(TencentMessageIntentSuperType.entries.toTypedArray())
  }.build()
}

internal enum class TencentMessageIntentSuperType(val offset: Int) {
  GUILDS(0),
  GUILD_MEMBERS(1),
  GUILD_MESSAGES(9),
  GUILD_MESSAGE_REACTIONS(10),
  DIRECT_MESSAGE(12),
  OPEN_FORUMS_EVENT(18),
  AUDIO_OR_LIVE_CHANNEL_MEMBER(19),
  CLIENT_MESSAGE(25), // 私聊 群聊事件
  INTERACTION(26),
  MESSAGE_AUDIT(27),
  FORUMS_EVENT(28),
  AUDIO_ACTION(29),
  PUBLIC_GUILD_MESSAGES(30)
}

@Serializable
internal data class TencentGuildUser(
  val id: String,
  val bot: Boolean,
  val avatar: String,
  val username: String,
  @SerialName("union_openid")
  val unionOpenid: String = "",
  @SerialName("union_user_account")
  val unionUserAccount: String = ""
)

@Serializable
internal data class TencentGuildMember(
  val nick: String,
  val roles: List<String>,
  @SerialName("joined_at")
  val joinedAt: String,
  val user: TencentGuildUser? = null
)

@Serializable
internal data class TencentMessageAttachment(
  val url: String // 下载地址
)

@Serializable
internal data class TencentMessageEmbed(
  /**
   * 标题
   */
  val title: String,
  /**
   * 消息弹窗内容
   */
  val prompt: String,
  /**
   * 缩略图
   */
  val thumbnail: TencentMessageEmbedThumbnail,
  /**
   * embed 字段数据
   */
  val fields: List<TencentMessageEmbedField>,
)

@Serializable
internal data class TencentMessageEmbedThumbnail(
  val url: String // 图片地址
)

@Serializable
internal data class TencentMessageEmbedField(
  val name: String
)

/**
 * 小程序模板
 */
@Serializable
internal data class TencentMessageArk(
  /**
   * ark模板id
   */
  @SerialName("template_id")
  val templateId: Int,
  val kv: List<TencentMessageArkKv> = listOf()
)
@Serializable
internal data class TencentMessageArkKv(
  val key: String,
  val value: String,
  val obj: List<TencentMessageArkObj> = listOf()
)

@Serializable
internal data class TencentMessageArkObj(
  @SerialName("obj_kv")
  val objKv: List<TencentMessageArkObjKv> = listOf()
)

@Serializable
internal data class TencentMessageArkObjKv(
  val key: String,
  val value: String
)

@Serializable
internal data class TencentMessageReference(
  /**
   * 需要引用回复的消息 id
   */
  @SerialName("message_id")
  val messageId: String,
  /**
   * 是否忽略获取引用消息详情错误，默认否
   */
  @SerialName("ignore_get_message_error")
  val ignoreGetMessageError: Boolean = false
)

@Serializable
internal data class TencentGuildMessage(
  /**
   * 消息id
   */
  val id: String,
  /**
   * 频道id
   */
  @SerialName("guild_id")
  val guildId: String,
  /**
   * 子频道id
   */
  @SerialName("channel_id")
  val channelId: String,
  /**
   * 用于私信场景下识别真实的来源频道id
   */
  @SerialName("src_guild_id")
  val srcGuildId: String? = null,
  /**
   * 消息内容
   */
  val content: String,
  /**
   * 用于消息间的排序，
   *
   * seq 在同一子频道中按从先到后的顺序递增，
   *
   * 不同的子频道之间消息无法排序。
   *
   * (目前只在消息事件中有值，2022年8月1日 后续废弃)
   */
  @SerialName("seq")
  @Deprecated("@since 22-08")
  val sequence: Long,
  /**
   * 子频道消息 seq，用于消息间的排序，
   *
   * seq 在同一子频道中按从先到后的顺序递增，不同的子频道之间消息无法排序
   */
  @SerialName("seq_in_channel")
  val sequenceInChannel: Long,
  /**
   * 消息创建时间 ISO8601 timestamp
   */
  val timestamp: String,
  /**
   * 消息编辑时间 ISO8601 timestamp
   */
  @SerialName("edited_timestamp")
  val editedTimestamp: String? = null,
  /**
   * 是否是@全员消息
   */
  @SerialName("mention_everyone")
  val mentionEveryone: Boolean = false,
  /**
   * 消息创建者
   */
  val author: TencentGuildUser,
  /**
   * 消息创建者的member信息
   */
  val member: TencentGuildMember,
  /**
   * 消息中@到的人
   */
  val mentions: List<TencentGuildUser>? = null,
  /**
   * 附件
   */
  val attachments: List<TencentMessageAttachment>? = null,
  /**
   * embed
   */
  val embeds: List<TencentMessageEmbed>? = null,
  /**
   * ark
   */
  val ark: List<TencentMessageArk>? = null,
  /**
   * 引用消息对象
   */
  @SerialName("message_reference")
  val messageReference: TencentMessageReference? = null
)

internal fun TencentGuildMessage.toMessageChain() = ""

sealed class MessageChain(
  private val sourceMessage: TencentGuildMessage
) : Message {
  val sourceId = sourceMessage.id
}

interface Message {
  /**
   * 给出可读的字符串
   */
  override fun toString(): String

  /**
   * 给出序列化保存的字符串
   */
  fun serialization(): String
}

data class PlainText(
  private val content: String
) : Message {
  override fun toString() = content
  override fun serialization() = content
}

data class TencentImage(
  val url: String,
) : Message {
  override fun toString() = url
  override fun serialization() = "{tencent:image:$url}"
}

@Suppress("NOTHING_TO_INLINE")
inline fun String.toPlainText(): PlainText = PlainText(this)

internal object TencentMessageTypeAsIntSerializer : KSerializer<TencentMessageType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentMessageType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentMessageType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentMessageType.fromValue(decoder.decodeInt())
}

enum class TencentMessageType(val code: Int) {
  PLAIN_TEXT(0), // 纯文本
  IMAGE(1), // 图文
  MARKDOWN(2), // markdown
  ARK(3), // 卡片
  EMBED(4); // 小程序
  companion object {
    private val TypeMap = entries.associateBy { it.code }
    fun fromValue(code: Int) = TypeMap[code] ?: PLAIN_TEXT
  }
}

@Serializable
class TencentMessage(
  val content: String,
  @SerialName("msg_type")
  @Serializable(with = TencentMessageTypeAsIntSerializer::class)
  var messageType: TencentMessageType,
  var image: String? = null,
  val markdown: String? = null,
  val keyboard: String? = null,
  val ark: String? = null,
  @SerialName("msg_id")
  var messageId: String? = null,
)
class TencentMessageBuilder private constructor(
  private val container: MutableList<Message>,
  messageSource: TencentMessageEvent? = null
) : MutableList<Message> by container {
  private val sourceMessageId: String? = messageSource?.messageId
  constructor(messageSource: TencentMessageEvent? = null) : this(mutableListOf(), messageSource)
  fun append(text: String) = this.apply {
    container.add(PlainText(text))
  }
  fun append(element: Message) = this.apply {
    container.add(element)
  }
  fun append(message: TencentMessage) = this.apply {
    when (message.messageType) {
      TencentMessageType.PLAIN_TEXT -> {
        append(PlainText(message.content))
      }
      TencentMessageType.IMAGE -> {
        if (message.image != null) {
          append(TencentImage(message.image!!))
        }
      }
      else -> {}
    }
  }
  // TODO
  fun append(other: TencentMessageBuilder) = this.apply {
    other.build().also { append(it) }
  }
  // TODO build其他类型消息
  fun build() = TencentMessage(
    content = container.filterIsInstance<PlainText>().joinToString("") { it.toString() },
    messageType = TencentMessageType.PLAIN_TEXT,
    messageId = sourceMessageId
  ).apply {
    val im = container.filterIsInstance<TencentImage>().firstOrNull()
    if (im != null) {
      messageType = TencentMessageType.IMAGE
      image = im.url
    }
  }
}
