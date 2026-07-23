package com.diyigemt.arona.communication

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 子频道类型
 */
@Serializable(with = TencentGuildChannelTypeIntSerializer::class)
enum class TencentGuildChannelType(val code: Int) {
  TEXT(0),
  RESERVE_0(1),
  VOICE(2),
  RESERVE_1(3),
  NODE(4),
  STREAM(10005),
  APPLICATION(10006),
  FORM(10007);

  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: TEXT
  }
}

internal object TencentGuildChannelTypeIntSerializer : KSerializer<TencentGuildChannelType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentGuildChannelType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentGuildChannelType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentGuildChannelType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentGuildChannelSubTypeIntSerializer::class)
enum class TencentGuildChannelSubType(val code: Int) {
  CHAT(0),
  ANNOUNCE(1),
  HELP(2),
  KOOK(3);

  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: CHAT
  }
}

internal object TencentGuildChannelSubTypeIntSerializer : KSerializer<TencentGuildChannelSubType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentGuildChannelSubType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentGuildChannelSubType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentGuildChannelSubType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentGuildChannelPrivateTypeIntSerializer::class)
enum class TencentGuildChannelPrivateType(val code: Int) {
  OPEN(0),
  ADMIN_ONLY(1),
  ADMIN_ONLY_MEMBER(2);

  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: OPEN
  }
}

internal object TencentGuildChannelPrivateTypeIntSerializer : KSerializer<TencentGuildChannelPrivateType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentGuildChannelPrivateType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentGuildChannelPrivateType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentGuildChannelPrivateType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentGuildChannelSpeakPermissionTypeIntSerializer::class)
enum class TencentGuildChannelSpeakPermissionType(val code: Int) {
  INVALID(0),
  ANY(1),
  ADMIN_ONLY_MEMBER(2);

  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: INVALID
  }
}

internal object TencentGuildChannelSpeakPermissionTypeIntSerializer :
  KSerializer<TencentGuildChannelSpeakPermissionType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentGuildChannelSpeakPermissionType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentGuildChannelSpeakPermissionType) =
    encoder.encodeInt(value.code)

  override fun deserialize(decoder: Decoder) = TencentGuildChannelSpeakPermissionType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentGuildChannelApplicationTypeIntSerializer::class)
enum class TencentGuildChannelApplicationType(val code: Int) {
  NULL(0),
  MOBA(1000000),
  MINI_GAME(1000001),
  VOTE(1000010),
  CAR(1000051),
  SCHEDULE(1000050),
  CODM(1000070),
  PEACE(1010000);

  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: NULL
  }
}

internal object TencentGuildChannelApplicationTypeIntSerializer : KSerializer<TencentGuildChannelApplicationType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentGuildChannelApplicationType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentGuildChannelApplicationType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentGuildChannelApplicationType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentWebsocketCallbackButtonChatTypeIntSerializer::class)
enum class TencentWebsocketCallbackButtonChatType(val code: Int) {
  Guild(0),
  Group(1),
  Friend(2),
  // 文档定义 chat_type 仅 0/1/2. 未知取值一律解析为 Unknown, 由 handler 白名单短路丢弃——绝不能静默兜底成
  // Group(1): 否则未知场景会被误路由到群链路(getOrCreate 群 Contact / 对不该回执的互动误发 PUT 回执).
  // code=-1 仅为占位, 该枚举只用于入站解码, 不会被反向编码下发.
  Unknown(-1);
  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: Unknown
  }
}

internal object TencentWebsocketCallbackButtonChatTypeIntSerializer : KSerializer<TencentWebsocketCallbackButtonChatType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentWebsocketCallbackButtonChatType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentWebsocketCallbackButtonChatType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentWebsocketCallbackButtonChatType.fromValue(decoder.decodeInt())
}

@Serializable(with = TencentWebsocketCallbackButtonTypeIntSerializer::class)
enum class TencentWebsocketCallbackButtonType(val code: Int) {
  MessageButton(11),
  QuickMenu(12),
  // 文档定义 11~20 (按钮点击/快捷菜单/反馈/清空会话/授权等), 但本链路仅支持 11/12. 其余取值一律解析为 Unknown,
  // 由 handler 显式白名单短路丢弃——绝不能静默兜底成 MessageButton(11), 否则 13~20 会被误当按钮点击处理
  // (甚至对无需 PUT 回执的类型发起回执). code=-1 仅为占位, 该枚举只用于入站解码, 不会被反向编码下发.
  Unknown(-1);
  companion object {
    private val map = entries.associateBy { it.code }
    fun fromValue(code: Int) = map[code] ?: Unknown
  }
}

internal object TencentWebsocketCallbackButtonTypeIntSerializer : KSerializer<TencentWebsocketCallbackButtonType> {
  override val descriptor = PrimitiveSerialDescriptor("TencentWebsocketCallbackButtonType", PrimitiveKind.INT)
  override fun serialize(encoder: Encoder, value: TencentWebsocketCallbackButtonType) = encoder.encodeInt(value.code)
  override fun deserialize(decoder: Decoder) = TencentWebsocketCallbackButtonType.fromValue(decoder.decodeInt())
}
