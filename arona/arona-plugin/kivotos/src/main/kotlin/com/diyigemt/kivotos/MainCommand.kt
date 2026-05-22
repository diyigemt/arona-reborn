package com.diyigemt.kivotos

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.kivotos.schema.kivotosUser


private val visitorMenu by lazy {
  tencentCustomMarkdown {
    +"访客菜单"
  } + tencentCustomKeyboard {
    row {
      subButton("注册")
    }
  }
}

private val playerMainMenu by lazy {
  tencentCustomKeyboard {
    row {
      subButton("咖啡厅", enter = true)
      subButton("背包", enter = true)
      subButton("装备", enter = true)
    }
    row {
      subButton("排行榜", enter = true)
      subButton("设置", enter = true)
      subButton("删号")
    }
  }.windowed()
}

private val playerMainMenuWithTitle by lazy {
  tencentCustomMarkdown {
    h1(KivotosCommand.primaryName)
  } + playerMainMenu
}

// 委托给 arona-core 的 commandButton, 把 "/{primary} {data}" 拼接交给统一原语;
// 本地包装继续保留, 因为 KivotosCommand.primaryName 是这个插件特定的命令前缀.
fun TencentCustomKeyboardRow.subButton(label: String, data: String = label, enter: Boolean = false) =
  commandButton(KivotosCommand.primaryName, label, data, enter)

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
      +"/$primaryName 排行榜, 进入排行榜"
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
