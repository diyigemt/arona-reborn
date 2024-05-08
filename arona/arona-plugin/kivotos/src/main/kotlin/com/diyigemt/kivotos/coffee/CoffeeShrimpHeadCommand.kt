package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.UnderDevelopment
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.TencentCustomKeyboard
import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.at
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.schema.UserDocument
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument

@UnderDevelopment
@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
class CoffeeShrimpHeadCommand : AbstractCommand(
  Kivotos,
  "摸胸",
  description = "咖啡厅摸胸指令",
  help = """
      /${KivotosCommand.primaryName} 咖啡厅 摸胸 日奈
    """.trimIndent()
) {
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  private val visitedStudents by requireObject<List<StudentSchema>>()
  private val touchedStudents by requireObject<List<StudentSchema>>()
  private val coffee by requireObject<CoffeeDocument>()
  private val kivotosUser by requireObject<UserDocument>()
  private val studentName by argument("学生名")
  suspend fun UserCommandSender.coffeeTouch() {
    val targetStudent = StudentSchema.StudentCache.filter {
      it.value.name == studentName
    }.values.firstOrNull()
    if (targetStudent == null) {
      val md = tencentCustomMarkdown {
        +"没法摸摸$studentName, 学生不存在"
        at()
      }
      sendMessage(md + kb)
      return
    }
    if (studentName !in visitedStudents.map { it.name }) {
      md append tencentCustomMarkdown {
        +"没法摸摸$studentName, 她没来访问呢"
        at()
      }
      sendMessage(md + kb)
      return
    }
//    val md = md + tencentCustomMarkdown {
//      +"你摸了摸${studentName}的胸, 好感+15 (${updates.third})"
//      if (updates.first) {
//        +"$studentName 的好感上升了, 当前等级: ${updates.second}"
//      }
//      at()
//    }
    sendMessage(md + kb)
  }
}
