package com.diyigemt.arona.arona.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class BaseConfig(
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
)

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = false // 是否启用md
)

@Serializable
data class TarotConfig(
  val fxxkDestiny: Boolean = false, // 是否启用逆天改命
  val dayOne: Boolean = true // 每天最多抽一次?
)

@Serializable
data class TrainerConfig(
  val overrideConfig: List<TrainerOverrideConfig> = listOf()
)
object TrainerOverrideTypeSerializer : KSerializer<TrainerOverrideType> {
  // TODO
  override fun deserialize(decoder: Decoder) = TrainerOverrideType.RAW
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
