package com.diyigemt.arona.test

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.TencentCallbackButtonEventResp
import com.diyigemt.arona.communication.event.TencentGuildMessageEvent
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.test",
  name = "hello",
  author = "diyigemt",
  version = "0.0.2",
  description = "test interaction"
)) {
  override fun onLoad() {

  }
}

object TestCommand : AbstractCommand(
  PluginMain,
  "测试"
) {
  suspend fun UserCommandSender.test() {
    val md = TencentTemplateMarkdown("102057194_1708227032") {
      append("title", "管理员按钮测试")
      append("content", " ")
      append("footer", " ")
    }
    val kb = tencentCustomKeyboard(bot.unionOpenidOrId) {
      row {
        button("1") {
          render {
            label = "普通用户测试"
          }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = "普通用户"
          }
        }
        button("2") {
          render {
            label = "管理员测试"
          }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = "管理员"
            permission = TencentKeyboardButtonActionPermissionData(
              type = TencentKeyboardButtonActionDataType.MANAGER
            )
          }
        }
      }
    }
    sendMessage(MessageChainBuilder().append(md).append(kb).build())
    withTimeoutOrNull(5000L) {
      nextButtonInteraction().also {
        it.result = TencentCallbackButtonEventResp.Failed
        sendMessage(MessageChainBuilder(it.eventId).append("点击了 ${it.buttonData} 按钮").build())
      }
    } ?: sendMessage("等待超时")
  }
}
