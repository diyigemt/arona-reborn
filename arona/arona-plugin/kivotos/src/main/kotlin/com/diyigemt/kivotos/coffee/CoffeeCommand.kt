package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.h1
import com.diyigemt.arona.communication.message.row
import com.diyigemt.arona.communication.message.tencentCustomKeyboard
import com.diyigemt.arona.communication.message.tencentCustomMarkdown
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.subButton
import com.github.ajalt.clikt.parameters.arguments.argument



@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object CoffeeCommand : AbstractCommand(
  Kivotos,
  "咖啡厅",
  description = "咖啡厅系列指令",
) {
  private val md = tencentCustomMarkdown {
    h1("夏莱附属咖啡厅")
  }

  suspend fun UserCommandSender.coffee() {
    val students = DatabaseProvider.dbQuery {
      val max = StudentSchema.count()
      StudentSchema.find {
        StudentTable.id inList IntArray(3) { (1..max.toInt()).random() }.toList()
      }.toList()
    }.map { it.name }
    val kb = tencentCustomKeyboard(bot.unionOpenidOrId) {
      (students + listOf("一键摸头")).windowed(2, 2, true).forEach { r ->
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
