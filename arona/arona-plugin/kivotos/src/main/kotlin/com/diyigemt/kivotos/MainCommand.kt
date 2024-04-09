package com.diyigemt.kivotos

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.schema.kivotosUser


private val visitorMenu by lazy {
  tencentCustomMarkdown {
    +"访客菜单"
  } + tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
    row {
      subButton("注册")
    }
  }
}

private val playerMainMenu by lazy {
  tencentCustomKeyboard(BotManager.getBot().unionOpenidOrId) {
    row {
      subButton("咖啡厅", enter = true)
      subButton("删号")
    }
  }
}

private val playerMainMenuWithTitle by lazy {
  tencentCustomMarkdown {
    h1(KivotosCommand.primaryName)
  } + playerMainMenu
}

fun TencentCustomKeyboardRow.subButton(label: String, data: String = label, enter: Boolean = false) {
  button(uuid(), label, "/${KivotosCommand.primaryName} $data", enter)
}

@Suppress("unused")
class KivotosCommand : AbstractCommand(
  Kivotos,
  primaryName,
  description = "赛博基沃托斯主菜单",
  help = tencentCustomMarkdown {
    +"欢迎来到赛博基沃托斯, 使用"
    list {
      +"/$primaryName, 打开主菜单"
      +"/$primaryName 咖啡厅, 进入咖啡厅"
      +"/$primaryName 竞技场, 进入竞技场"
      +"/$primaryName 删号, 删除账号"
    }
  }.content
) {
  suspend fun UserCommandSender.menu() {
    currentContext.setObject("md", tencentCustomMarkdown { })
    currentContext.setObject("kb", tencentCustomKeyboard { })
    currentContext.setObject("kivotosUser", kivotosUser())
    if (currentContext.invokedSubcommand != null) {
      return
    }
    // TODO 注册
    val isRegister = true
    if (isRegister) {
      // TODO 签到
      val isTodayFirstLogin = true
      if (isTodayFirstLogin) {
        val loginResult = tencentCustomMarkdown {
          +"签到成功, 获得100清辉石"
        }
        sendMessage(loginResult + playerMainMenu)
      } else {
        sendMessage(playerMainMenuWithTitle)
      }
    } else {
      sendMessage(visitorMenu)
    }
  }
  companion object {
    const val primaryName = "赛博基沃托斯"
  }
}
