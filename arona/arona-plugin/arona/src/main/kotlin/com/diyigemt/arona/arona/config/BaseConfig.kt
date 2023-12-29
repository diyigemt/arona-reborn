package com.diyigemt.arona.arona.config

import kotlinx.serialization.Serializable
@Serializable
data class BaseConfig(
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
)

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = true // 是否启用md
)

@Serializable
data class TarotConfig(
  val fxxkDestiny: Boolean = false // 是否启用逆天改命
)

@Serializable
data class TrainerConfig(
  val overrideConfig: List<TrainerOverrideConfig> = listOf()
)
@Serializable
data class TrainerOverrideConfig(
  val type: TrainerOverrideType,
  val name: List<String>,
  val value: String,
)
@Serializable
enum class TrainerOverrideType {
  RAW
}
