package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrNull
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.enum
import kotlinx.serialization.Serializable

enum class Server(val tag: String) {
  B("B服"),
  CN("官服"),
  HK("港澳台"),
  KR("韩服"),
  GLOBAL("国际服"),
  ASIA("亚服"),
  US("美服"),
  JP("日服")
}

@Serializable
data class TotalAssaultConfig(
  val defaultTotalAssault: Server = Server.JP, // 总力战档线默认数据
  val defaultTotalAssaultEx: Server = Server.JP, // 大决战档线默认数据
) : PluginWebuiConfig() {
  override fun check() {}
}

@Suppress("unused")
object TotalAssaultCommand : AbstractCommand(
  Arona,
  "总力档线",
  description = "提供当期总力档线",
  help = """
    /总力档线 日服|官服|B服
  """.trimIndent()
) {
  private val server by argument(name = "服务器", help = "可选值: 官服 B服 日服").enum<Server> { it.tag }
    .optional()

  suspend fun UserCommandSender.totalAssault() {
    val config = readPluginConfigOrNull<TotalAssaultConfig>(Arona)
    val name = when (server ?: config?.defaultTotalAssault ?: Server.JP) {
      Server.B -> "国服B服总力战档线"
      Server.CN -> "国服官服总力战档线"
      Server.JP -> "日服总力战档线"
      else -> {
        sendMessage("没数据源")
        return
      }
    }
    if (config == null && server == null) {
      sendMessage("未配置默认服务器,发送日服数据,配置可随时在webui更改")
    }
    CommandManager.executeCommand(this, PlainText("/攻略 $name"))
  }
}

@Suppress("unused")
object TotalAssaultExCommand : AbstractCommand(
  Arona,
  "大决战档线",
  description = "提供当期大决战档线",
  help = """
    /大决战档线 日服
  """.trimIndent()
) {
  private val server by argument(name = "服务器", help = "可选值: 官服 B服 日服").enum<Server> { it.tag }
    .optional()

  suspend fun UserCommandSender.totalAssault() {
    val config = readPluginConfigOrDefault(Arona, TotalAssaultConfig())
    when (server ?: config.defaultTotalAssaultEx) {
      Server.B,
      Server.CN -> sendMessage("还没开呢, 别急")
      Server.ASIA, Server.HK, Server.KR, Server.GLOBAL, Server.US -> sendMessage("没数据源")
      Server.JP -> CommandManager.executeCommand(this, PlainText("/攻略 日服大决战档线"))
    }
  }
}
