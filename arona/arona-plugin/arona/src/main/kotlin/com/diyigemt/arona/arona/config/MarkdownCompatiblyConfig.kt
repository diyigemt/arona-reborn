package com.diyigemt.arona.arona.config

import kotlinx.serialization.Serializable

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = true // 是否启用md
)
