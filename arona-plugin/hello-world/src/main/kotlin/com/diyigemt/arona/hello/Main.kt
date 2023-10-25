package com.diyigemt.arona.hello

import com.diyigemt.arona.communication.TencentGuildMessageEvent
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.hello",
  author = "diyigemt",
  version = "2.3.3",
  description = "hello world"
)) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentGuildMessageEvent> {
      it.sendMessage("hello world!")
    }
    logger.info("hello world!")
  }
}
