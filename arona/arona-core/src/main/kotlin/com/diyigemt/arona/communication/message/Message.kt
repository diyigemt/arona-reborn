@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.communication.message

import com.diyigemt.arona.communication.*
import com.diyigemt.arona.communication.contact.*
import com.diyigemt.arona.communication.event.TencentMessageEvent
import com.diyigemt.arona.communication.message.TencentAt.Companion.toSourceTencentAt
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue

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
    .reduce { acc, i -> acc or (1 shl i) }

  fun buildPublicBot() = this.apply {
    append(TencentMessageIntentSuperType.GUILDS)
    append(TencentMessageIntentSuperType.GUILD_MEMBERS)
    append(TencentMessageIntentSuperType.GUILD_MESSAGE_REACTIONS)
    append(TencentMessageIntentSuperType.DIRECT_MESSAGE)
    append(TencentMessageIntentSuperType.CLIENT_MESSAGE)
    append(TencentMessageIntentSuperType.INTERACTION)
    append(TencentMessageIntentSuperType.MESSAGE_AUDIT)
    append(TencentMessageIntentSuperType.AUDIO_ACTION)
    append(TencentMessageIntentSuperType.PUBLIC_GUILD_MESSAGES)
  }

  fun buildGuildBot() = this.apply {
    append(TencentMessageIntentSuperType.GUILDS)
    append(TencentMessageIntentSuperType.DIRECT_MESSAGE)
    append(TencentMessageIntentSuperType.PUBLIC_GUILD_MESSAGES)
  }

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
  INTERACTION(26), // 回调按钮被点击
  MESSAGE_AUDIT(27),
  FORUMS_EVENT(28),
  AUDIO_ACTION(29),
  PUBLIC_GUILD_MESSAGES(30)
}

@Serializable
internal data class TencentGuildUserRaw(
  override val id: String,
  val avatar: String,
  val username: String,
  val bot: Boolean = false,
  @SerialName("union_openid")
  val unionOpenid: String = "",
  @SerialName("union_user_account")
  val unionUserAccount: String = "",
) : ContactRaw

@Serializable
internal data class TencentGuildMemberRaw(
  @SerialName("joined_at")
  val joinedAt: String,
  val nick: String = "",
  val roles: List<String> = listOf(),
  var user: TencentGuildUserRaw? = null,
  @SerialName("guild_id")
  val guildIid: String = "",
)

@Serializable
internal data class TencentMessageAttachmentRaw(
  val url: String, // 下载地址
)

@Serializable
internal data class TencentMessageAttachmentRaw0(
  @SerialName("content_type")
  val url: String = "", // 下载地址
  val size: String = "",
  val width: String = "",
  val height: String = "",
  val filename: String = "",
  val contentType: String = "",
)

@Serializable
internal data class TencentMessageEmbedRaw(
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
  val thumbnail: TencentMessageEmbedThumbnailRaw,
  /**
   * embed 字段数据
   */
  val fields: List<TencentMessageEmbedField>,
)

@Serializable
internal data class TencentMessageEmbedThumbnailRaw(
  val url: String, // 图片地址
)

@Serializable
internal data class TencentMessageEmbedField(
  val name: String,
)

/**
 * 小程序模板
 */
@Serializable
internal data class TencentMessageArkRaw(
  /**
   * ark模板id
   */
  @SerialName("template_id")
  val templateId: Int,
  val kv: List<TencentMessageArkKv> = listOf(),
)

@Serializable
internal data class TencentMessageArkKv(
  val key: String,
  val value: String,
  val obj: List<TencentMessageArkObj> = listOf(),
)

@Serializable
internal data class TencentMessageArkObj(
  @SerialName("obj_kv")
  val objKv: List<TencentMessageArkObjKv> = listOf(),
)

@Serializable
internal data class TencentMessageArkObjKv(
  val key: String,
  val value: String,
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
  val ignoreGetMessageError: Boolean = false,
)

@Serializable
internal data class TencentGuildRaw(
  override val id: String,
  val name: String,
  val icon: String,
  @SerialName("owner_id")
  val ownerId: String,
  val owner: Boolean,
  @SerialName("member_count")
  val memberCount: Int,
  @SerialName("max_members")
  val maxMembers: Int,
  val description: String,
  @SerialName("joined_at")
  val joinedAt: String,
  @SerialName("op_user_id")
  val opUserId: String = "",
) : ContactRaw

@Serializable
internal data class TencentGuildChannelRaw(
  override val id: String,
  @SerialName("guild_id")
  val guildId: String,
  val name: String,
  val type: TencentGuildChannelType,
  @SerialName("sub_type")
  val subType: TencentGuildChannelSubType = TencentGuildChannelSubType.CHAT,
  val position: Int,
  @SerialName("parent_id")
  val parentId: String,
  @SerialName("owner_id")
  val ownerId: String,
  @SerialName("private_type")
  val privateType: TencentGuildChannelPrivateType = TencentGuildChannelPrivateType.OPEN,
  @SerialName("speak_permission")
  val speakPermission: TencentGuildChannelSpeakPermissionType = TencentGuildChannelSpeakPermissionType.ANY,
  @SerialName("application_id")
  val applicationId: TencentGuildChannelApplicationType = TencentGuildChannelApplicationType.NULL,
  // TODO 处理权限序列化和反序列化
  val permissions: String = "",
) : ContactRaw

internal interface ContactRaw {
  val id: String
}

@Serializable
internal data class TencentBotGroupEventRaw(
  @SerialName("group_openid")
  val groupOpenId: String,
  @SerialName("op_member_openid")
  val opMemberId: String,
  val timestamp: Long,
) : ContactRaw {
  override val id get() = groupOpenId
}

@Serializable
internal data class TencentBotFriendEventRaw(
  val openid: String,
  val timestamp: String,
) : ContactRaw {
  override val id get() = openid
}

// Sprint 3.5(b): Java 默认 \s 不覆盖 NBSP (U+00A0) 与全角空格 (U+3000), Kotlin Char.isWhitespace
// 同样不吞 NBSP. 客户端常见把这两类粘进 @bot 消息, 旧实现 split(" ") 会把整段当 PlainText, at 解析失败.
// 显式列出字符集, 既不依赖 UNICODE_CHARACTER_CLASS flag, 也不放进所有 unicode 空白避免误伤 ZWSP 之类.
private val TencentMessageWhitespaceRegex = Regex("[\\s\\u00A0\\u3000]+")
private fun Char.isTencentMessageWhitespace(): Boolean =
  isWhitespace() || this == ' ' || this == '　'

internal interface TencentMessageRaw : ContactRaw {
  /**
   * 消息创建者
   */
  val author: ContactRaw

  /**
   * 消息内容
   */
  val content: String

  /**
   * 消息创建时间 ISO8601 timestamp
   */
  val timestamp: String

  /**
   * 附件 大部分情况下是图片
   */
  val attachments: List<TencentMessageAttachmentRaw>?
  fun toMessageChain(): MessageChain {
    val messageChain = MessageChainImpl(this.id)
    with(this.content.trim { it.isTencentMessageWhitespace() }) {
      if (isNotBlank()) {
        split(TencentMessageWhitespaceRegex)
          .takeIf { it.size >= 2 }
          ?.run {
            first().toSourceTencentAt()?.also { at ->
              messageChain.delegate.add(at)
              messageChain.delegate.add(PlainText(this.subList(1, this.size).joinToString(" ")))
            }
          } ?: messageChain.delegate.add(PlainText(this))
      }
    }
    if (this.attachments?.isNotEmpty() == true) {
      messageChain.delegate.addAll(
        this.attachments!!.map { im ->
          TencentOnlineImage("", "", 0L, im.url)
        }
      )
    }
    return messageChain
  }
}

@Serializable
internal data class TencentChannelMessageRaw(
  /**
   * 消息id
   */
  override val id: String,
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
  override val content: String,
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
  override val timestamp: String,
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
  override val author: TencentGuildUserRaw,
  /**
   * 消息创建者的member信息
   */
  val member: TencentGuildMemberRaw,
  /**
   * 消息中@到的人
   */
  val mentions: List<TencentGuildUserRaw>? = null,
  /**
   * 附件
   */
  override val attachments: List<TencentMessageAttachmentRaw>? = null,
  /**
   * embed
   */
  val embeds: List<TencentMessageEmbedRaw>? = null,
  /**
   * ark
   */
  val ark: List<TencentMessageArkRaw>? = null,
  /**
   * 引用消息对象
   */
  @SerialName("message_reference")
  val messageReference: TencentMessageReference? = null,
) : TencentMessageRaw

/**
 * 频道私聊消息原始结构
 */
internal typealias TencentGuildPrivateMessageRaw = TencentChannelMessageRaw

@Serializable
internal data class TencentFriendMessageAuthorRaw(
  @SerialName("user_openid")
  val userOpenid: String,
) : ContactRaw {
  override val id: String
    get() = userOpenid // TODO union_id
}

@Serializable
internal data class TencentGroupMessageAuthorRaw(
  @SerialName("member_openid")
  val memberOpenid: String,
) : ContactRaw {
  override val id: String
    get() = memberOpenid // TODO union_id
}

@Serializable
internal data class TencentFriendMessageRaw(
  override val id: String,
  override val author: TencentFriendMessageAuthorRaw,
  override val content: String,
  override val timestamp: String,
  override val attachments: List<TencentMessageAttachmentRaw>? = null,
) : TencentMessageRaw

@Serializable
internal data class TencentGroupMessageRaw(
  override val id: String,
  override val author: TencentGroupMessageAuthorRaw,
  override val content: String,
  override val timestamp: String,
  override val attachments: List<TencentMessageAttachmentRaw>? = null,
  @SerialName("group_openid")
  val groupId: String,
) : TencentMessageRaw

internal const val EmptyMessageId = ""

sealed interface MessageChain : Message, Collection<Message> {
  val sourceId: String
  val eventId: String?

  companion object {
    fun MessageChain.hasExternalMessage() = this.filterIsInstance<PlainText>().size != this.size
  }
}

internal class MessageChainImpl(
  override val sourceId: String,
  override val eventId: String? = null,
  internal val delegate: MutableCollection<Message>,
) : MessageChain, Collection<Message> by delegate {
  constructor(sourceId: String, eventId: String? = null) : this(sourceId, eventId, ConcurrentLinkedQueue())

  override fun toString() = delegate.joinToString(" ") { it.toString() }

  // serialization 要求逐元素拼接, 不加分隔符——PlainText 本身保留空白, 结构消息 (At/image/markdown)
  // 的 serialization 已自带语义边界, 再插空格会污染重解析.
  override fun serialization() = delegate.joinToString("") { it.serialization() }
}

object MessageChainAsStringSerializer : KSerializer<MessageChain> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MessageChain", PrimitiveKind.STRING)

  // 纯文本 (lossy) 串化: 结构消息 (At/image/markdown) 的 serialization() 仅保留文本表示形式, 反序列化
  // 后只得到单元素 PlainText, 不保证还原为原先的结构类型. 调用方需明白这一约束.
  override fun deserialize(decoder: Decoder): MessageChain =
    PlainText(decoder.decodeString()).toMessageChain()

  override fun serialize(encoder: Encoder, value: MessageChain) {
    encoder.encodeString(value.serialization())
  }
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
  operator fun plus(other: Message) = MessageChainBuilder(this, other).build()
}

data class PlainText(
  private val content: String,
) : Message {
  override fun toString() = content
  override fun serialization() = content
}

interface TencentResource {
  val resourceId: String
  val resourceUuid: String
  val ttl: Long
  val size: Long
}

@Serializable
sealed class TencentMarkdown

// Sprint 2.3: markdown 序列化走 bracket + JSON 格式. 把内容/结构交给 kotlinx.serialization 转义,
// 避免自己发明一套会跟 markdown 语法 (`\n`, `]`, `:`) 撞车的小语法.
// 约定与 MessageChainAsStringSerializer 的 lossy 契约一致: 反序列化可读, 不保证还原结构类型.
private val TencentMarkdownSerializationJson = Json {
  encodeDefaults = true
}
private const val TencentCustomMarkdownSerializationPrefix = "[tencent:markdown:custom:"
private const val TencentTemplateMarkdownSerializationPrefix = "[tencent:markdown:template:"

@Serializable
data class TencentCustomMarkdown(
  var content: String,
) : Message, TencentMarkdown() {
  infix fun append(other: TencentCustomMarkdown) {
    content += if (content.endsWith("\n")) {
      other.content
    } else {
      "\n" + other.content
    }
  }

  infix fun insertTo(other: TencentCustomMarkdown) {
    other.content = content + if (content.endsWith("\n")) {
      other.content
    } else {
      "\n" + other.content
    }
  }

  operator fun plus(other: TencentCustomMarkdown): TencentCustomMarkdown {
    return TencentCustomMarkdown(
      content + if (content.endsWith("\n")) {
        other.content
      } else {
        "\n" + other.content
      }
    )
  }

  operator fun plus(other: String): TencentCustomMarkdown {
    return TencentCustomMarkdown(
      content + if (content.endsWith("\n")) {
        other
      } else {
        "\n" + other
      }
    )
  }

  override fun serialization(): String {
    val escaped = TencentMarkdownSerializationJson.encodeToString(String.serializer(), content)
    return "$TencentCustomMarkdownSerializationPrefix$escaped]"
  }

  companion object {
    val PlaceHolderMarkdown = TencentCustomMarkdown("\u200b")
    val EmptyMarkdown = TencentCustomMarkdown("\u200b")
  }
}

@Serializable
data class TencentTemplateMarkdown(
  @SerialName("custom_template_id")
  val id: String,
  val params: List<TencentMarkdownParam>,
) : Message, TencentMarkdown() {
  constructor(id: String, block: TencentMarkdownParam.Companion.TencentMarkdownParamBuilder.() -> Unit) :
      this(id, TencentMarkdownParam.Companion.TencentMarkdownParamBuilder().apply(block).build())

  override fun serialization(): String {
    val encoded = TencentMarkdownSerializationJson.encodeToString(serializer(), this)
    return "$TencentTemplateMarkdownSerializationPrefix$encoded]"
  }
}

@Serializable
  data class TencentMarkdownParam(
  val key: String,
  val values: List<String>,
) {
  companion object {
    class TencentMarkdownParamBuilder {
      private val store: MutableList<TencentMarkdownParam> = mutableListOf()
      fun append(key: String, value: String) = apply { store.add(TencentMarkdownParam(key, listOf(value))) }
      fun build() = store.toList()
    }
  }
}

data class TencentAt(
  val target: String,
) : Message {
  override fun toString() = "@${target}"
  override fun serialization() = "[tencent:at:${target}]"

  companion object {
    private val matcher = Regex("^\\[tencent:at:(\\w+)]$")
    private val tencentMatcher = Regex("^<@!(\\w+)>$")
    private val readableMatcher = Regex("^@(\\w+)$")

    /**
     * 构造一个at这个contact的消息
     */
    fun Contact.at() = TencentAt(this.unionOpenidOrId)

    fun String.toTencentAt(): TencentAt? {
      val matchResult = matcher.matchEntire(this) ?: return null
      return TencentAt(matchResult.groupValues[1])
    }

    fun String.toSourceTencentAt(): TencentAt? {
      val matchResult = tencentMatcher.matchEntire(this) ?: return null
      return TencentAt(matchResult.groupValues[1])
    }

    fun String.toReadableTencentAt(): TencentAt? {
      val matchResult = readableMatcher.matchEntire(this) ?: return null
      return TencentAt(matchResult.groupValues[1])
    }

    val serializer = object : KSerializer<TencentAt> {
      override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TencentAt", PrimitiveKind.STRING)

      override fun deserialize(decoder: Decoder): TencentAt =
        decoder.decodeString().toSourceTencentAt() ?: TencentAt("")

      override fun serialize(encoder: Encoder, value: TencentAt) = encoder.encodeString(value.serialization())

    }

    val tencentSerializer = object : KSerializer<TencentAt> {
      override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TencentAt", PrimitiveKind.STRING)

      override fun deserialize(decoder: Decoder): TencentAt = decoder.decodeString().toTencentAt() ?: TencentAt("")

      override fun serialize(encoder: Encoder, value: TencentAt) = encoder.encodeString(value.serialization())

    }

  }
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
  EMBED(4), // 小程序
  FILE(7); // 文件

  companion object {
    private val TypeMap = entries.associateBy { it.code }
    fun fromValue(code: Int) = TypeMap[code] ?: PLAIN_TEXT
  }
}

internal object TencentRichMessageTypeAsIntSerializer : KSerializer<TencentRichMessageType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentRichMessageType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentRichMessageType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentRichMessageType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentRichMessageTypeAsIntSerializer::class)
enum class TencentRichMessageType(val code: Int) {
  IMAGE(1), // 图片
  VIDEO(2), // 视频
  VOICE(3), // 语音
  FILE(4); // 文件

  companion object {
    private val TypeMap = entries.associateBy { it.code }
    fun fromValue(code: Int) = TypeMap[code] ?: IMAGE
  }
}

@Serializable
sealed class TencentMessage(
  @EncodeDefault
  open val content: String = "",
  @EncodeDefault
  open var image: String? = null,
  @EncodeDefault
  open var markdown: TencentMarkdown? = null,
  @EncodeDefault
  open var keyboard: TencentKeyboard? = null,
  @EncodeDefault
  open val ark: String? = null,
  @SerialName("msg_id")
  @EncodeDefault
  open var messageId: String? = null,
  @SerialName("event_id")
  @EncodeDefault
  open val eventId: String? = null,
  @SerialName("msg_seq")
  @EncodeDefault
  open var messageSequence: Int = 1,
)

@Serializable
data class TencentGroupMessage(
  @Transient
  override val content: String = "",
  @SerialName("msg_type")
  @Serializable(with = TencentMessageTypeAsIntSerializer::class)
  @EncodeDefault
  var messageType: TencentMessageType = TencentMessageType.PLAIN_TEXT,
  @Transient
  override var image: String? = null,
  var media: TencentMessageMediaInfo? = null,
  @Transient
  override var markdown: TencentMarkdown? = null,
  @Transient
  override var keyboard: TencentKeyboard? = null,
  @Transient
  override val ark: String? = null,
  @SerialName("msg_id")
  @Transient
  override var messageId: String? = null,
  @SerialName("event_id")
  @Transient
  override val eventId: String? = null,
  @SerialName("msg_seq")
  @Transient
  override var messageSequence: Int = 1,
) : TencentMessage(content, image, markdown, keyboard, ark, messageId, eventId, messageSequence)

@Serializable
data class TencentGuildMessage(
  @Transient
  override val content: String = "",
  @Transient
  override var image: String? = null,
  @Transient
  override var markdown: TencentMarkdown? = null,
  @Transient
  override var keyboard: TencentKeyboard? = null,
  @Transient
  override val ark: String? = null,
  @SerialName("msg_id")
  @Transient
  override var messageId: String? = null,
  @SerialName("event_id")
  @Transient
  override val eventId: String? = null,
  @SerialName("msg_seq")
  @Transient
  override var messageSequence: Int = 1,
) : TencentMessage(content, image, markdown, keyboard, ark, messageId, eventId, messageSequence)

@Serializable
data class TencentMessageMediaInfo(
  @SerialName("file_info")
  val fileInfo: String,
  @SerialName("file_uuid")
  val fileUuid: String = "",
  val ttl: Long = 0L,
)

@Serializable
data class TencentRichMessage @OptIn(ExperimentalSerializationApi::class) constructor(
  val url: String? = null,
  @SerialName("file_type")
  @EncodeDefault
  val fileType: TencentRichMessageType = TencentRichMessageType.IMAGE,
  @SerialName("srv_send_msg")
  @EncodeDefault
  val srvSendMsg: Boolean = true,
  @SerialName("file_data")
  @EncodeDefault
  val fileData: String? = null,
)

private val lfSimplified = Regex("^\n\n")

// Sprint 2.3: 反向映射 TencentMessage → Message 元素的共享 helper. TencentMessageBuilder 和
// MessageChainBuilder 都需要把收到的 TencentMessage 展开成链上的结构消息, 逻辑重复, 抽成 helper.
// 选型说明: 图片反向用 TencentGuildImage——和 TencentMessageBuilder.build() 的正向分派契约对齐
// (TencentGuildImage → IMAGE type, url 字段), 再 build 能 round-trip 回等价 TencentGroupMessage.
// TencentOfflineImage / TencentGuildLocalImage 依赖文件上传状态, 无法从 url 字段复原.
private fun MutableList<Message>.appendTencentImageFromUrl(url: String?) {
  if (!url.isNullOrBlank()) add(TencentGuildImage(url))
}

private fun MutableList<Message>.appendTencentMarkdown(markdown: TencentMarkdown?) {
  // TencentMarkdown 自身非 Message, 但两个具体子类都实现 Message. 用 smart cast 分支 add.
  when (markdown) {
    is TencentCustomMarkdown -> add(markdown)
    is TencentTemplateMarkdown -> add(markdown)
    null -> Unit
  }
}

// 注意: keyboard 反向映射暂不实现——TencentTempleKeyboard / TencentCustomKeyboard 的 serialization()
// 仍是 TODO(), 若把 keyboard 吸收进链再调 chain.serialization() 会立即炸. 反向映射等下一轮和
// keyboard.serialization 补齐一起做.

class TencentMessageBuilder private constructor(
  private val container: MutableList<Message>,
  private val messageSequence: Int = 1,
  messageSource: TencentMessageEvent? = null,
) : MutableList<Message> by container {
  private var sourceMessageId: String? = messageSource?.message?.sourceId
  private var eventId: String? = messageSource?.message?.eventId

  constructor(messageSource: TencentMessageEvent? = null, messageSequence: Int = 1) : this(
    mutableListOf(),
    messageSequence,
    messageSource,
  )

  constructor(sourceId: String, messageSequence: Int = 1) : this(mutableListOf(), messageSequence) {
    sourceMessageId = sourceId
  }

  fun append(text: String) = this.apply {
    container.add(PlainText(text))
  }

  fun append(element: Message) = this.apply {
    when (element) {
      is MessageChain -> append(element)
      else -> container.add(element)
    }
  }

  fun append(element: MessageChain) = this.apply {
    sourceMessageId = element.sourceId
    eventId = element.eventId
    container.addAll(element)
  }

  private fun absorbSource(message: TencentMessage) {
    sourceMessageId = message.messageId ?: sourceMessageId
    eventId = message.eventId ?: eventId
  }

  fun append(message: TencentGroupMessage) = this.apply {
    absorbSource(message)
    if (message.content.isNotBlank()) container.add(PlainText(message.content))
    when (message.messageType) {
      TencentMessageType.PLAIN_TEXT -> Unit // content 已追加
      TencentMessageType.IMAGE -> container.appendTencentImageFromUrl(message.image)
      TencentMessageType.MARKDOWN -> {
        container.appendTencentMarkdown(message.markdown)
      }
      // FILE 只带 media.fileInfo, 没有可逆的 URL 或文件字节表达, 当前 Message 层也没有对应的结构类型;
      // 伪造 image/offlineImage 会让后续 build() 误当 IMAGE 发出, 故直接 skip.
      TencentMessageType.FILE -> Unit
      // ARK / EMBED 当前 Message 层无对应结构消息, 待后续扩展.
      TencentMessageType.ARK, TencentMessageType.EMBED -> Unit
    }
  }

  fun append(message: TencentGuildMessage) = this.apply {
    // Guild 消息没有 msg_type 枚举, 所有字段并列可同时出现. 逐字段展开即可.
    absorbSource(message)
    if (message.content.isNotBlank()) container.add(PlainText(message.content))
    container.appendTencentImageFromUrl(message.image)
    container.appendTencentMarkdown(message.markdown)
  }

  fun append(message: TencentMessage) = this.apply {
    // 分派到具体子类版本; 两个子类各自 absorbSource + 按字段展开, 此处不再重复 absorb.
    when (message) {
      is TencentGuildMessage -> append(message)
      is TencentGroupMessage -> append(message)
    }
  }

  fun append(other: TencentMessageBuilder) = this.apply {
    // other 的 build() 可能丢失尚未落地的 source/eventId 上下文, 故直接沿用 other 已积累的容器元素.
    container.addAll(other.container)
    sourceMessageId = other.sourceMessageId ?: sourceMessageId
    eventId = other.eventId ?: eventId
  }

  // TODO build其他类型消息
  fun build(isPrivateChannel: Boolean = false): TencentMessage {
    val content = container
      .filterIsInstance<PlainText>()
      .joinToString("\n") { it.toString() }
      .takeIf { it.isNotEmpty() } ?: ""
    val im = container.filterIsInstance<TencentImage>().lastOrNull()
    val md = container.filterIsInstance<TencentMarkdown>().lastOrNull()?.also {
      if (it is TencentCustomMarkdown) {
        it.content = it.content.replace(lfSimplified, "\n")
      }
    }
    val kb = container.filterIsInstance<TencentKeyboard>().lastOrNull()
    if (isPrivateChannel) {
      return TencentGuildMessage(
        content = content,
        image = im?.url?.encodeURLPath(),
        markdown = md,
//        keyboard = kb,
        messageId = sourceMessageId,
        eventId = eventId,
        messageSequence = messageSequence
      )
    }
    return TencentGroupMessage(
      content = content,
      messageType = TencentMessageType.PLAIN_TEXT,
      messageId = sourceMessageId,
      eventId = eventId,
      messageSequence = messageSequence
    ).apply {
      when (im) {
        is TencentOfflineImage -> {
          messageType = TencentMessageType.FILE
          media = TencentMessageMediaInfo(
            fileInfo = im.resourceId
          )
        }

        is TencentGuildImage -> {
          messageType = TencentMessageType.IMAGE
          image = im.url.encodeURLPath()
        }

        else -> {}
      }
    }.apply {
      when (md) {
        is TencentTemplateMarkdown, is TencentCustomMarkdown -> {
          messageType = TencentMessageType.MARKDOWN
          markdown = md
        }

        else -> {}
      }
      when (kb) {
        is TencentKeyboard -> {
          messageType = TencentMessageType.MARKDOWN
          keyboard = kb
        }

        else -> {}
      }
    }
  }

  // 频道专有multipart
  fun buildMultipart(): MultiPartFormDataContent {
    val rawIm = container.filterIsInstance<TencentGuildLocalImage>().lastOrNull()
    val content = container
      .filterIsInstance<PlainText>()
      .joinToString("\n") { it.toString() }
      .takeIf { it.isNotEmpty() } ?: ""
    return MultiPartFormDataContent(
      formData {
        append("content", content)
        append("msg_id", sourceMessageId ?: "")
        append("event_id", eventId ?: "")
        append("msg_seq", messageSequence)
        if (rawIm != null) {
          append("file_image", rawIm.raw, Headers.build {
            append(HttpHeaders.ContentType, "image/png")
            append(HttpHeaders.ContentDisposition, "filename=\"ktor.png\"")
          })
        }
      }
    )
  }
}

class MessageChainBuilder private constructor(
  private val container: MutableList<Message>,
  private var sourceMessageId: String? = null,
  private var eventId: String? = null,
) : MutableList<Message> by container {
  constructor(sourceMessageId: String? = null, eventId: String? = null) : this(
    mutableListOf(),
    sourceMessageId,
    eventId
  )

  constructor(messageChain: MessageChain) : this(mutableListOf(), "") {
    append(messageChain)
  }

  constructor(vararg chan: Message) : this(mutableListOf(*chan), "")

  fun append(text: String) = this.apply {
    container.add(PlainText(text))
  }

  fun append(element: Message) = this.apply {
    when (element) {
      is MessageChain -> append(element)
      else -> container.add(element)
    }
  }

  fun append(element: MessageChain) = this.apply {
    sourceMessageId = element.sourceId.takeIf { it.isNotBlank() } ?: sourceMessageId
    eventId = element.eventId.takeIf { !it.isNullOrBlank() } ?: eventId
    container.addAll(element)
  }

  fun append(message: TencentMessage) = this.apply {
    sourceMessageId = message.messageId ?: sourceMessageId
    eventId = message.eventId ?: eventId
    if (message.content.isNotBlank()) container.add(PlainText(message.content))
    when (message) {
      is TencentGuildMessage -> {
        container.appendTencentImageFromUrl(message.image)
        container.appendTencentMarkdown(message.markdown)
      }
      is TencentGroupMessage -> when (message.messageType) {
        TencentMessageType.PLAIN_TEXT -> Unit
        TencentMessageType.IMAGE -> container.appendTencentImageFromUrl(message.image)
        TencentMessageType.MARKDOWN -> {
          container.appendTencentMarkdown(message.markdown)
        }
        // FILE / ARK / EMBED 在当前 Message 层无对应结构, 与 TencentMessageBuilder 保持一致不追加.
        TencentMessageType.FILE, TencentMessageType.ARK, TencentMessageType.EMBED -> Unit
      }
    }
  }

  fun append(other: TencentMessageBuilder) = this.apply {
    // TencentMessageBuilder 的内部 container 属 private, 跨类不可直取. 反向展开走 build() 产出的
    // TencentMessage 再按类型分派即可——append(TencentMessage) 内部已做 absorbSource + 字段展开.
    append(other.build())
  }

  operator fun plus(other: Message) = this.append(other)

  // TODO build其他类型消息
  fun build(): MessageChain {
    // Sprint 3.5(a): 旧实现把 builder 自己 (其 container 委托) 当 chain 的 delegate, 调用 build() 后继续
    // builder.append(...) 会污染已发出 chain. 这里改成快照, 修掉别名 bug. MessageChainImpl 自身仍保持
    // 可变 delegate 不动, 因为内部构造路径 (TencentMessageRaw.toMessageChain 等) 还会直接写 delegate;
    // 一步到位换不可变实现会放大改动面.
    return MessageChainImpl(sourceMessageId ?: EmptyMessageId, eventId, container.toMutableList())
  }
}

@Serializable
internal data class MessageReceiptImpl(
  val id: String = "",
  val timestamp: String = "",
) {
  context(Contact)
  fun toMessageReceipt() = MessageReceipt(this, this@Contact)
}

data class MessageReceipt<out C : Contact> internal constructor(
  private val internalReceipt: MessageReceiptImpl,
  val target: C,
) {
  val id get() = internalReceipt.id
  val timestamp get() = internalReceipt.timestamp
  suspend fun recall() {
    when (target) {
      is FriendUser -> {
        target.bot.callOpenapi(
          TencentEndpoint.DeleteFriendMessage,
          mapOf("openid" to target.id, "message_id" to id)
        ) {
          method = HttpMethod.Delete
        }
      }

      is Group -> {
        target.bot.callOpenapi(
          TencentEndpoint.DeleteGroupMessage,
          mapOf("group_openid" to target.id, "message_id" to id)
        ) {
          method = HttpMethod.Delete
        }
      }

      is GuildMember -> {
        target.bot.callOpenapi(
          TencentEndpoint.DeleteGuildMemberMessage,
          mapOf("channel_id" to target.id, "message_id" to id)
        ) {
          method = HttpMethod.Delete
        }
      }

      is Guild, is Channel -> {
        target.bot.callOpenapi(
          TencentEndpoint.DeleteGuildMessage,
          mapOf("guild_id" to target.id, "message_id" to id)
        ) {
          method = HttpMethod.Delete
        }
      }
    }
  }
}

val MessageReceipt<*>?.isFailed
  get() = this == null

fun Message.toMessageChain(): MessageChain = when (this) {
  is MessageChain -> this
  else -> MessageChainImpl("", null, mutableListOf(this))
}

fun Message.toMessageChain(sourceId0: String, eventId: String?): MessageChain =
  MessageChainBuilder(sourceId0, eventId).append(this).build()
