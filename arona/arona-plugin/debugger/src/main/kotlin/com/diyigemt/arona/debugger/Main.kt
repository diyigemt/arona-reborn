package com.diyigemt.arona.debugger

import com.diyigemt.arona.communication.event.Event
import com.diyigemt.arona.communication.event.MessagePreSendEvent
import com.diyigemt.arona.communication.event.TencentBotEvent
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = BuildConfig.ID,
  name = BuildConfig.NAME,
  author = BuildConfig.AUTHOR,
  version = BuildConfig.VERSION,
  description = BuildConfig.DESCRIPTION
)) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<Event> {
      when (it) {
        is MessagePreSendEvent -> return@subscribeAlways
        is TencentBotEvent -> it.bot.logger.info(it.toString())
        else -> logger.info(it.toString())
      }
    }
  }
}
