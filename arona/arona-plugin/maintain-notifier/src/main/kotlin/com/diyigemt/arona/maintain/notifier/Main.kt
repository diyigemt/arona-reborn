package com.diyigemt.arona.maintain.notifier

import com.diyigemt.arona.communication.event.TencentMessageEvent
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.utils.datetimeToInstant
import com.diyigemt.arona.utils.toDateTime
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.terminal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.maintain.notifier",
    name = "maintain-notifier",
    author = "diyigemt",
    version = "0.1.1",
    description = "维护通知器"
  )
) {
  override fun onLoad() {
    pluginEventChannel().subscribeAlways<TencentMessageEvent> {
      MessageChainBuilder(it.message.sourceId)
        .append("Arona正在维护中.")
        .append("原因: ${NotifyConfig.reason}")
        .append("起始时间: ${NotifyConfig.start}")
        .append("结束时间: ${NotifyConfig.end}")
        .append("更新日志: ${NotifyConfig.doc}")
        .build()
        .also { m ->
          subject.sendMessage(m)
        }
    }
  }
}

object NotifyConfig : AutoSavePluginData("notify-config") {
  var start: String by value("")
  var end: String by value("")
  var reason: String by value("")
  var doc: String by value("")
}

@Suppress("unused")
class UpdateUpdateConsoleCommand : CommandLineSubCommand, CliktCommand(name = "maintain", help = "刷新更新日志") {
  override fun run() {
    val start = terminal.prompt("起始时间", default = NotifyConfig.start) as String
    val end = terminal.prompt(
      "结束时间",
      default = datetimeToInstant(start).plus(30, DateTimeUnit.MINUTE).toDateTime()
    ) as String
    val reason = terminal.prompt("原因", default = NotifyConfig.reason) as String
    val doc = terminal.prompt("更新日志", default = NotifyConfig.doc) as String
    NotifyConfig.start = start
    NotifyConfig.end = end.takeIf { it.split(":").size == 3 } ?: "$end:00"
    NotifyConfig.reason = reason
    NotifyConfig.doc = doc
  }
}
