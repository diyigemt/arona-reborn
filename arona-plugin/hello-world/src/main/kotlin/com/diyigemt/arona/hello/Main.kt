package com.diyigemt.arona.hello

import com.diyigemt.arona.communication.event.TencentGuildMessageEvent
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.hello",
  name = "hello",
  author = "diyigemt",
  version = "2.3.3",
  description = "hello world"
)) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentGuildMessageEvent> {
      it.subject.sendMessage(MessageChainBuilder(it.message.sourceId).append("hello world!").build())
    }
  }
}
