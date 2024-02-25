package com.diyigemt.arona.test

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.TencentCallbackButtonEventResult
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.withTimeoutOrNull

object PluginMain : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.arona.test",
  name = "hello",
  author = "diyigemt",
  version = "0.0.12",
  description = "test interaction"
)) {
  override fun onLoad() {

  }
}

object TestCommand : AbstractCommand(
  PluginMain,
  "测试"
) {
  private val row by argument(help = "行数").int()
  private val col by argument(help = "列数").int()
  suspend fun UserCommandSender.test() {
    val md = TencentTemplateMarkdown("102057194_1708227032") {
      append("title", "管理员按钮测试")
      append("content", " ")
      append("footer", " ")
    }
    val kb = tencentCustomKeyboard(bot.unionOpenidOrId) {
      (0 until row).forEach { i ->
        row {
          (0 until col).forEach { j ->
            button((i * row + j).toString()) {
              render {
                label = (i * row + j).toString()
              }
              action {
                type = TencentKeyboardButtonActionType.CALLBACK
                data = "普通用户"
              }
            }
          }
        }

      }
    }
    sendMessage(MessageChainBuilder().append(md).append(kb).build())
    withTimeoutOrNull(5000L) {
      nextButtonInteraction().also {
        it.accept()
        sendMessage("点击了 ${it.buttonData} 按钮")
      }
    } ?: sendMessage("等待超时")
  }
}
