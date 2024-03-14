package com.diyigemt.arona.command

import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.Serializable

@Serializable
data class BaseConfig(
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
) : PluginWebuiConfig() {
  override fun check() {}
}

@Serializable
data class MarkdownCompatiblyConfig(
  val enable: Boolean = true // 是否启用md
)
