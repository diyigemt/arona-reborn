package com.diyigemt.kivotos.configuration

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.updateUserPluginConfig
import com.diyigemt.arona.communication.message.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.coffee.CoffeeConfig
import com.diyigemt.kivotos.subButton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class ConfigurationCommand : AbstractCommand(
  Kivotos,
  "设置",
  description = "设置系列指令",
  help = tencentCustomMarkdown {
    list {
      +"/赛博基沃托斯 设置 咖啡厅, 查看咖啡厅相关设置"
    }
  }.content
) {
  private val md = tencentCustomMarkdown {
    h1("赛博基沃托斯设置")
  }

  suspend fun UserCommandSender.configuration() {
    if (currentContext.invokedSubcommand == null) {
      sendMessage(md + kb)
      return
    }
  }

  companion object {
    private val kb by lazy {
      tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
        row {
          subButton("咖啡厅", "设置 咖啡厅", enter = true)
          subButton("没做,放着好看", "", enter = false)
        }
      }
    }
  }
}

@SubCommand(forClass = ConfigurationCommand::class)
@Suppress("unused")
@OptIn(ExperimentalSerializationApi::class)
class ConfigurationCoffeeCommand : AbstractCommand(
  Kivotos,
  "咖啡厅",
  description = "设置咖啡厅系列指令",
) {
  suspend fun UserCommandSender.coffeeConfig() {
    val configure = readUserPluginConfigOrDefault(Kivotos, CoffeeConfig())
    val kb = renderConfig(configure)
    val md = tencentCustomMarkdown {
      h1("咖啡厅设置")
      +"交互设置有效期为60s"
      at()
    }
    var message = sendMessage(kb + md)
    withTimeoutOrNull(60 * 1000L) {
      while (true) {
        val select = nextButtonInteraction()
        select.accept()
        when (select.buttonData) {
          "inviteDoubleCheck" -> {
            configure.inviteDoubleCheck = !configure.inviteDoubleCheck
          }

          "touchAfterInvite" -> {
            configure.touchAfterInvite = !configure.touchAfterInvite
          }
        }
        message?.recall()
        updateUserPluginConfig(Kivotos, configure)
        message = sendMessage(md + renderConfig(configure))
      }
    }
    message?.recall()
  }

  private fun UserCommandSender.renderConfig(configure: CoffeeConfig): TencentCustomKeyboard {
    return tencentCustomKeyboard {
      row {
        button {
          render {
            label = (if (configure.inviteDoubleCheck) "关闭" else "打开") + "邀请二次确认"
          }
          action {
            data = "inviteDoubleCheck"
            type = TencentKeyboardButtonActionType.CALLBACK
          }
          selfOnly()
        }
      }
      row {
        button {
          render {
            label = (if (configure.touchAfterInvite) "关闭" else "打开") + "邀请后自动摸头"
          }
          action {
            data = "touchAfterInvite"
            type = TencentKeyboardButtonActionType.CALLBACK
          }
          selfOnly()
        }
      }
    }
  }
}
