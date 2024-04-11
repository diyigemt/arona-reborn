@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.command

import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class BaseConfig(
  @EncodeDefault
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
) : PluginWebuiConfig() {
  override fun check() {}
}

@Serializable
data class MarkdownCompatiblyConfig(
  @EncodeDefault
  val enable: Boolean = true // 是否启用md
)
