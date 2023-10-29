package com.diyigemt.arona.hello

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.GuildChannelCommandSender
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

object TestCommand : AbstractCommand(
  PluginMain,
  "测试"
) {
  suspend fun GuildChannelCommandSender.test() {
    sendMessage("这是通过指令触发的消息")
  }
}