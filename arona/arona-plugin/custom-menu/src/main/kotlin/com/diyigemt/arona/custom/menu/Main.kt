package com.diyigemt.arona.custom.menu

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrNull
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.Serializable

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.custom.menu",
    name = "custom-menu",
    author = "diyigemt",
    version = "0.2.0",
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
  val buttons: List<CustomMenuButton>
) {
  constructor(vararg buttons: CustomMenuButton) : this(listOf(*buttons))
}
@Serializable
data class CustomMenuConfig(
  val rows: List<CustomMenuRow> = listOf()
) : PluginWebuiConfig() {
  constructor(vararg rows: CustomMenuRow) : this(listOf(*rows))
  fun toCustomKeyboard(): TencentCustomKeyboard {
    return tencentCustomKeyboard {
      rows.forEachIndexed { i, r ->
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
    val menu = readUserPluginConfigOrNull<CustomMenuConfig>(PluginMain)
    val md = TencentTemplateMarkdown("102057194_1702387539") {
      append("title", if (menu == null) "默认菜单, 可在webui自定义" else "快捷菜单")
    }
    val kb = (menu ?: CustomMenuConfig.DefaultMenu).toCustomKeyboard()
    sendMessage(MessageChainBuilder().append(md).append(kb).build())
  }
}