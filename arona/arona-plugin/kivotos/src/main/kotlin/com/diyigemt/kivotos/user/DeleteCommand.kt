package com.diyigemt.kivotos.user

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.schema.UserDocument
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
class DeleteCommand : AbstractCommand(
  Kivotos,
  "删号",
  description = "删除账号"
) {
  private val kivotosUser by requireObject<UserDocument>()
  private val confirmCode by argument("code", help = "删号代码").optional()
  private fun generateNumber(): String = (1..6).map { "0123456789".random() }.joinToString("")
  suspend fun UserCommandSender.register() {
    if (confirmCode != null) {
      val code = DatabaseProvider.redisDbQuery {
        get("$REDIS_KEY.${user.id}")
      }
      if (code == confirmCode) {
        // TODO 删号
        if (kivotosUser.deleteAccount()) {
          sendMessage("删除成功")
        } else {
          sendMessage("删除失败")
        }
      } else {
        val message = tencentCustomMarkdown {
          h1("错误")
          +"验证码不存在"
          +"请先执行 \"/${KivotosCommand.primaryName} 删号\" 指令生成验证码 "
          at()
        } + tencentCustomKeyboard {
          row {
            button("生成验证码", "/${KivotosCommand.primaryName} 删号", true)
          }
        }
        sendMessage(message)
      }
      return
    }
    val code = generateNumber()
    val md = tencentCustomMarkdown {
      h1("警告")
      +"确认要删除账号吗, 删除后将立即生效无法找回!"
      +"如果真的需要删除, 请点击确认按钮并附上随机生成的6位数字"
      +"验证码: $code"
      +"如 /${KivotosCommand.primaryName} 删号 123456"
    }
    val kb = tencentCustomKeyboard {
      row {
        button {
          render {
            label = "确认删号"
          }
          action {
            data = "/${KivotosCommand.primaryName} 删号 "
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
    DatabaseProvider.redisDbQuery {
      set("$REDIS_KEY.${user.id}", code)
      expire("$REDIS_KEY.${user.id}", 60u)
    }
    sendMessage(md + kb)
  }
  companion object {
    private const val REDIS_KEY = "kivotos.delete."
  }
}
