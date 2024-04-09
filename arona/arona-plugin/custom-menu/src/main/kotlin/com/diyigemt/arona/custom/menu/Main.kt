@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.custom.menu

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrNull
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.custom.menu",
    name = "custom-menu",
    author = "diyigemt",
    version = "0.3.0",
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

  override fun check() {
    while (rows.size > 5) {
      rows.removeLast()
    }
    rows.forEach {
      while (it.buttons.size > 5) {
        it.buttons.removeLast()
      }
    }
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
class CustomMenuCommand : AbstractCommand(
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
