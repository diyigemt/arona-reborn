package com.diyigemt.arona.arona.config

import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.Serializable

@Serializable
data class BaseConfig(
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
) : PluginWebuiConfig()

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = false // 是否启用md
)
