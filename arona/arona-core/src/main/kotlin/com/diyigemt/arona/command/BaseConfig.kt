@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.command

import com.diyigemt.arona.webui.pluginconfig.ConfigItem
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class BaseConfig(
  @EncodeDefault
  @ConfigItem(label = "Markdown 兼容设置", description = "控制命令回复中的 Markdown 行为")
  val markdown: MarkdownCompatiblyConfig = MarkdownCompatiblyConfig()
) : PluginWebuiConfig()

@Serializable
data class MarkdownCompatiblyConfig(
  @EncodeDefault
  @ConfigItem(label = "启用 Markdown", description = "关闭后将尽量使用普通文本回复")
  val enable: Boolean = true // 是否启用md
)
