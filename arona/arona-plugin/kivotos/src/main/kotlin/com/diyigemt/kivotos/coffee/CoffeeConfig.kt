package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.webui.pluginconfig.ConfigItem
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@Serializable
data class CoffeeConfig(
  @EncodeDefault
  @ConfigItem(label = "邀请二次确认", description = "邀请学生进咖啡厅前要求再次确认")
  var inviteDoubleCheck: Boolean = true,
  @EncodeDefault
  @ConfigItem(label = "邀请后摸头", description = "邀请学生后自动执行摸头动作")
  var touchAfterInvite: Boolean = true,
) : PluginWebuiConfig()
