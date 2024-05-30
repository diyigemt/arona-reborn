@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.custom.menu

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrNull
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.pluginconfig.PluginConfigCheckResult
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.custom.menu",
    name = "custom-menu",
    author = "diyigemt",
    version = "0.4.0",
    description = "快捷菜单"
  )
) {
  override fun onLoad() {

  }
}
@Serializable
data class CustomMenuButton(
  val label: String,
  val data: String,
  val enter: Boolean
)
@Serializable
data class CustomMenuRow(
  @EncodeDefault
  val buttons: MutableList<CustomMenuButton> = mutableListOf()
) {
  constructor(vararg buttons: CustomMenuButton) : this(mutableListOf(*buttons))
}
@Serializable
data class CustomMenuConfig(
  @EncodeDefault
  val rows: MutableList<CustomMenuRow> = mutableListOf()
) : PluginWebuiConfig() {
  constructor(vararg rows: CustomMenuRow) : this(mutableListOf(*rows))

  override fun check(): PluginConfigCheckResult {
    while (rows.size > 5) {
      rows.removeLast()
    }
    rows.forEach {
      while (it.buttons.size > 5) {
        it.buttons.removeLast()
      }
    }
    return PluginConfigCheckResult.PluginConfigCheckAccept()
  }
  fun toCustomKeyboard(botAppId: String): TencentCustomKeyboard {
    return tencentCustomKeyboard(botAppId) {
      this@CustomMenuConfig.rows.forEachIndexed { i, r ->
        row {
          r.buttons.forEachIndexed { j, b ->
            button("$i-$j") {
              render {
                label = b.label
              }
              action {
                data = b.data
                enter = b.enter
              }
            }
          }
        }
      }
    }
  }
  companion object {
    val DefaultMenu = CustomMenuConfig(
      CustomMenuRow(
        CustomMenuButton("官服总力档线", "/总力档线 官服", true),
        CustomMenuButton("B服总力档线", "/总力档线 B服", true),
      ),
      CustomMenuRow(
        CustomMenuButton("日服总力档线", "/总力档线 日服", true),
        CustomMenuButton("日服大决战档线", "/大决战档线 日服", true),
      ),
      CustomMenuRow(
        CustomMenuButton("国际服未来视", "/攻略 未来视", true),
        CustomMenuButton("国服日程", "/攻略 国服日程", true),
      ),
      CustomMenuRow(
        CustomMenuButton("日服总力", "/攻略 日服总力", true),
        CustomMenuButton("国际服总力", "/攻略 国际服总力", true),
      ),
      CustomMenuRow(
        CustomMenuButton("塔罗牌", "/塔罗牌", true),
        CustomMenuButton("十连", "/十连", true),
      )
    )
  }
}
@Suppress("unused")
object CustomMenuCommand : AbstractCommand(
  PluginMain, "菜单", description = "提供快捷菜单, 或者默认菜单"
) {
  suspend fun UserCommandSender.menu() {
    val mdConfig = readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown
    if (!mdConfig.enable) {
      sendMessage(MessageChainBuilder()
        .append("请先在webui开启markdown支持")
        .append("需要NTQQ或QQ8.9.85以上版本才能显示")
        .append("文档: https://doc.arona.diyigemt.com/v2/manual/webui")
        .append("webui仅支持桌面端,未对移动端适配")
        .build()
      )
      return
    }
    val menu = readUserPluginConfigOrNull<CustomMenuConfig>(PluginMain)
    val md = TencentTemplateMarkdown("102057194_1708227032") {
      append("title", if (menu == null) "默认菜单" else "快捷菜单")
      append("content", " ")
      append("footer", " ")
    }
    val kb = (menu ?: CustomMenuConfig.DefaultMenu).toCustomKeyboard(bot.unionOpenidOrId)
    sendMessage(MessageChainBuilder().append(md).append(kb).build())
  }
}

@Serializable
data class CustomCommandConfig(
  @EncodeDefault
  val commands: Map<String, String> = mapOf()
) : PluginWebuiConfig() {
  override fun check(): PluginConfigCheckResult {
    return if (commands.entries.size > 10) {
      PluginConfigCheckResult.PluginConfigCheckReject("不能超过10条指令")
    } else {
      PluginConfigCheckResult.PluginConfigCheckAccept()
    }
  }
}

@Suppress("unused")
object CustomCommandCommand : AbstractCommand(
  PluginMain, "快捷指令", description = "懒狗专用, 解放双手"
) {
  private val c by argument("指令id").default("")
  suspend fun UserCommandSender.command() {
    val map = readUserPluginConfigOrDefault(BuildInCommandOwner, default = CustomCommandConfig()).commands
    val command = map[c]
    if (command != null) {
      launch {
        CommandManager.executeCommand(this@command, command.toPlainText(), true).await()
      }
    } else {
      val md = tencentCustomMarkdown {
        h1("没有找到指令对象")
        +"已配置的指令有:"
        if (map.isNotEmpty()) {
          list {
            map.entries.forEach {
              +"${it.key} -> ${it.value}"
            }
          }
        } else {
          +"空"
          +"请先前往webui配置"
        }
      }
      if (map.isNotEmpty()) {
        val kb = tencentCustomKeyboard {
          row {
            map.entries.forEach {
              button(it.key, it.value.trim(), true)
            }
          }
        }.also { it.windowed() }
        sendMessage(md + kb)
      } else {
        sendMessage(md)
      }
    }
  }
}
