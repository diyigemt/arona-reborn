package com.diyigemt.arona.plana.command

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.GroupCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plana.PluginMain

/**
 * 色色功能菜单。
 *
 * 第一行「色色排行」是指令按钮(任何人可点, 自动发送 /色色排行); 第二行「可以色色」「不许色色」是回调按钮,
 * 通过腾讯按钮原生权限限定仅群管理员可点。审查开关不再有独立文字指令, 只能经此菜单按钮触发,
 * 回调由 [PluginMain] 的全局订阅处理。
 *
 * 仅群聊可用: 开关语义是"本群审查", 好友/频道下 contact 语义不同, 菜单按钮也无意义。
 */
@Suppress("unused")
class SeseMenuCommand : AbstractCommand(
  PluginMain,
  "色色",
  description = "打开色色功能菜单",
  help = "/色色"
) {
  suspend fun UserCommandSender.seseMenu() {
    if (this !is GroupCommandSender) {
      sendMessage("该指令仅支持群聊使用")
      return
    }
    sendMessage(MessageChainBuilder().append(MENU_MARKDOWN).append(menuKeyboard()).build())
  }

  private companion object {
    private val MENU_MARKDOWN = tencentCustomMarkdown {
      h1("色色")
      +"点击下方按钮使用对应功能"
      +"「可以色色 / 不许色色」仅群管理员可点击"
    }

    /**
     * 每次发送都重建一份 keyboard: 回调按钮的 [com.diyigemt.arona.communication.message.TencentCustomKeyboard0.botAppid]
     * 会在发送阶段按当前 bot 补齐, 顶层模板复用本身安全, 但保持无共享状态更省心。
     */
    private fun menuKeyboard(): TencentCustomKeyboard = tencentCustomKeyboard {
      row {
        // 排行任何人可看: 指令按钮直接复用既有 /色色排行 命令。
        button("plana-sese-rank") {
          render { label = "色色排行" }
          action {
            type = TencentKeyboardButtonActionType.COMMAND
            data = "/色色排行"
            enter = true
            permission = TencentKeyboardButtonActionPermissionData(
              type = TencentKeyboardButtonActionDataType.ANY_ONE
            )
          }
        }
      }
      row {
        // 可以色色 = 关闭本群审查(默认态); 回调按钮 + 仅管理员权限。
        button("plana-audit-off") {
          render { label = "可以色色" }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = PluginMain.AUDIT_CALLBACK_OFF
            permission = TencentKeyboardButtonActionPermissionData(
              type = TencentKeyboardButtonActionDataType.MANAGER
            )
          }
        }
        // 不许色色 = 开启本群审查; 回调按钮 + 仅管理员权限。
        button("plana-audit-on") {
          render { label = "不许色色" }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = PluginMain.AUDIT_CALLBACK_ON
            permission = TencentKeyboardButtonActionPermissionData(
              type = TencentKeyboardButtonActionDataType.MANAGER
            )
          }
        }
      }
    }
  }
}
