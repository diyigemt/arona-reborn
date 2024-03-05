package com.diyigemt.kivotos

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.UnderDevelopment
import com.diyigemt.arona.communication.BotManager
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.communication.message.button
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.utils.uuid
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

@Suppress("unused")
object Kivotos : AronaPlugin(AronaPluginDescription(
  id = "com.diyigemt.kivotos",
  name = "kivotos",
  author = "diyigemt",
  version = "0.0.2",
  description = "hello world"
)) {
  override fun onLoad() {}
}

private val visitorMenu by lazy {
  tencentCustomMarkdown {
    + "访客菜单"
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
          + "签到成功, 获得100清辉石"
        }
        sendMessage(loginResult + playerMainMenu)
      } else {
        sendMessage(playerMainMenuWithTitle)
      }
    } else {
      sendMessage(visitorMenu)
    }
  }
  @SubCommand
  @Suppress("unused")
  object RegisterCommand : AbstractCommand(
    Kivotos,
    "注册",
    description = "注册账号"
  ) {
    suspend fun UserCommandSender.register() {
      sendMessage("注册成功")
    }
  }
  @SubCommand
  @Suppress("unused")
  object DeleteCommand : AbstractCommand(
    Kivotos,
    "删号",
    description = "删除账号"
  ) {
    private const val REDIS_KEY = "kivotos.delete."
    private val confirmCode by argument("code", help = "删号代码").optional()
    private fun generateNumber(): String = (1..6).map { "0123456789".random() }.joinToString("")
    suspend fun UserCommandSender.register() {
      if (confirmCode != null) {
        val code = redisDbQuery {
          get("$REDIS_KEY.${user.id}")
        }
        if (code == confirmCode) {
          // TODO 删号
          sendMessage("删除成功")
        } else {
          sendMessage("代码不存在")
        }
        return
      }
      val code = generateNumber()
      val md = tencentCustomMarkdown {
        h1("警告")
        + "确认要删除账号吗, 删除后将立即生效无法找回!"
        + "如果真的需要删除, 请点击确认按钮并附上随机生成的6位数字"
        + "验证码: $code"
        + "如 /赛博基沃托斯 删号 123456"
      }
      val kb = tencentCustomKeyboard(bot.unionOpenidOrId) {
        row {
          button {
            render {
              label = "确认删号"
            }
            action {
              data = "/赛博基沃托斯 删号 "
            }
          }
          button {
            render {
              label = "取消"
              visitedLabel = "已取消"
            }
          }
        }
      }
      redisDbQuery {
        set("$REDIS_KEY.${user.id}", code)
        expire("$REDIS_KEY.${user.id}", 60u)
      }
      sendMessage(md + kb)
    }
  }

  @SubCommand
  @Suppress("unused")
  object CoffeeCommand : AbstractCommand(
    Kivotos,
    "咖啡厅",
    description = "咖啡厅系列指令",
  ) {
    private val md = tencentCustomMarkdown {
      h1("夏莱附属咖啡厅一楼")
    }
    suspend fun UserCommandSender.coffee() {
      val studentList = listOf("白子", "静子", "日奈")
      val kb = tencentCustomKeyboard(bot.unionOpenidOrId) {
        (studentList + listOf("一键摸头")).windowed(2, 2, true).forEach { r ->
          row {
            r.forEach { c ->
              if (c == "一键摸头") {
                subButton(c, "咖啡厅 一键摸头")
              } else {
                subButton("摸摸$c", "咖啡厅 摸头 $c")
              }
            }
          }
        }
      }
      sendMessage(md + kb)
    }
    @SubCommand
    @Suppress("unused")
    object CoffeeCommand : AbstractCommand(
      Kivotos,
      "摸头",
      description = "咖啡厅摸头指令",
    ) {
      private val studentName by argument("学生名")
      suspend fun UserCommandSender.coffeeTouch() {
        sendMessage("你摸了摸$studentName, 功德+3")
      }
    }
  }
}
