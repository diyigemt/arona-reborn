package com.diyigemt.arona.arona.config

import kotlinx.serialization.Serializable

@Serializable
data class BaseConfig(
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
)

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = false // 是否启用md
)
