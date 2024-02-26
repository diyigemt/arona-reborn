package com.diyigemt.arona.test

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.withTimeoutOrNull

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = "com.diyigemt.arona.test",
    name = "hello",
    author = "diyigemt",
    version = "0.0.12",
    description = "test interaction"
  )
) {
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
            button(i * row + j) {
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

object TestMarkdownCommand : AbstractCommand(
  PluginMain,
  "测试2"
) {
  suspend fun UserCommandSender.test() {
    val test = tencentCustomMarkdown {
      title {
        content = "一级标题"
      }
      title {
        content = "二级标题"
        level = TitleElement.TitleElementLevel.H2
      }
      +"文字测试"
      text {
        content = "文字测试2"
        style = TextElement.TextElementStyle.Bold
      }
      "文字测试3" style TextElement.TextElementStyle.Italic
      br()
      link {
        href = "https://doc.arona.diyigemt.com/v2/manual/webui"
      }
      link {
        "https://doc.arona.diyigemt.com/v2/manual/webui" to "webui"
      }
      image {
        href = "https://arona.cdn.diyigemt.com/image/some/别急.png"
        w = 200
        h = 218
        placeholder = "别急"
      }
      block {
        + "测试"
        + "引用"
        text {
          content = "测试"
        }
        text {
          "测试2" style TextElement.TextElementStyle.BoldItalic
        }
      }
      divider()
      list {
        + "测试列表"
        + "无序列表"
      }
      list {
        + "测试列表"
        + "无序列表"
        hasIndex = true
      }
      list {
        list("测试嵌套") {
          "这是嵌套的列表1-1" style TextElement.TextElementStyle.StrikeThrough
          "这是嵌套的列表1-2" style TextElement.TextElementStyle.StarItalic
        }
        list("测试嵌套2") {
          "这是嵌套的列表2-1" style TextElement.TextElementStyle.StrikeThrough
          "这是嵌套的列表2-2" style TextElement.TextElementStyle.StarItalic
        }
      }
    }
    MessageChainBuilder().append(test).build().also{
      sendMessage(it)
    }
  }
}