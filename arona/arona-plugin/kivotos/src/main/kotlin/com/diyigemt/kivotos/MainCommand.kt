package com.diyigemt.kivotos

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.UnderDevelopment
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.coffee.CoffeeCommand
import com.diyigemt.kivotos.user.DeleteCommand
import com.diyigemt.kivotos.user.RegisterCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional


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
      subButton("咖啡厅")
      subButton("课程表")
    }
  }
}

private val playerMainMenuWithTitle by lazy {
  tencentCustomMarkdown {
    h1("赛博基沃托斯")
  } + playerMainMenu
}

fun TencentCustomKeyboardRow.subButton(label: String, data: String = label, enter: Boolean = false) {
  button(uuid(), label, "/赛博基沃托斯 $data", enter)
}

@UnderDevelopment
@Suppress("unused")
object KivotosCommand : AbstractCommand(
  Kivotos,
  "赛博基沃托斯",
  description = "赛博基沃托斯主菜单",
  help = """
    欢迎来到赛博基沃托斯, 使用
    
    /赛博基沃托斯, 打开主菜单
    
    /赛博基沃托斯 注册, 注册账号
    
    /赛博基沃托斯 删号, 删除账号
  """.trimIndent()
) {
  suspend fun UserCommandSender.menu() {
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
}
