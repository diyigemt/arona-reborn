package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.subButton
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

private suspend fun UserCommandSender.coffee() = CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument?> {
  find(filter = idFilter(userDocument().id)).limit(1).firstOrNull()
} ?: CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument> {
  CoffeeDocument(userDocument().id).also {
    insertOne(it)
  }
}


@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object CoffeeCommand : AbstractCommand(
  Kivotos,
  "咖啡厅",
  description = "咖啡厅系列指令",
) {
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  suspend fun UserCommandSender.coffee0() {
    tencentCustomMarkdown {
      h1("夏莱附属咖啡厅")
    } insertTo md
    val coffee = coffee()
    currentContext.setObject("coffee", coffee)
    updateCoffeeStudents(currentContext.invokedSubcommand == null)
  }

  @Suppress("lower_case")
  private const val MORNING_TIME = "03:00:00"
  private const val AFTERNOON_TIME = "15:00:00"
  private suspend fun UserCommandSender.updateCoffeeStudents(sendMessage: Boolean) {
    var coffee = currentContext.findObject<CoffeeDocument>()!!
    md append tencentCustomMarkdown {
      +"当前咖啡厅等级: ${coffee.level}"
    }
    if (checkStudentUpdate(coffee.lastStudentUpdateTime)) {
      // 需要更新来访的学生
      val students = DatabaseProvider.dbQuery {
        val max = StudentSchema.count()
        StudentSchema.find {
          StudentTable.id inList IntArray(studentCount(coffee.level)) { (1..max.toInt()).random() }.toList()
        }.toList()
      }
      coffee.updateStudents(students.map { it.id.value })
      coffee = coffee()
    } else if (checkStudentTouchedUpdate(coffee.lastTouchTime)) {
      coffee.updateTouchedStudents(coffee.students, false)
      coffee = coffee()
    }
    currentContext.setObject("coffee", coffee)
    val visitedStudents = DatabaseProvider.dbQuery {
      StudentSchema.find {
        StudentTable.id inList coffee.students
      }.toList()
    }
    val touchedStudents = DatabaseProvider.dbQuery {
      StudentSchema.find {
        StudentTable.id inList coffee.touchedStudents
      }.toList()
    }
    currentContext.setObject("visitedStudents", visitedStudents)
    currentContext.setObject("touchedStudents", touchedStudents)
    md append tencentCustomMarkdown {
      +"来访学生"
      list {
        visitedStudents.map { it.name }.forEach { s ->
          +s
        }
      }
    }
    kb append buildTouchButton(
      bot.unionOpenidOrId,
      visitedStudents.map { it.name }
    )
    if (sendMessage) {
      md append tencentCustomMarkdown {
        at()
      }
      sendMessage(md + kb)
    }
  }
  // 检查学生刷新时间
  private fun checkStudentUpdate(last0: String): Boolean {
    val now = now()
    val nowDatetime = now.toDateTime()
    val last = last0.toInstant()
    val morning = "${currentDate()} $MORNING_TIME"
    val afternoon = "${currentDate()} $AFTERNOON_TIME"
    // 超过12小时 肯定需要更新
    return (now - last).inWholeHours >= 12L ||
      morning in last0..nowDatetime ||
      afternoon in last0..nowDatetime
  }
  // 检查学生摸头刷新时间 从第一次摸开始算起3小时刷新
  private fun checkStudentTouchedUpdate(last0: String): Boolean {
    val now = now()
    val last = last0.toInstant()
    val duration = now - last
    return duration.inWholeHours >= 3
  }

  private fun studentCount(level: Int) = when (level) {
    1 -> 1
    in (1..4) -> 2
    in (5..7) -> 3
    else -> 4
  }
}

@Serializable
data class CoffeeDocument(
  @BsonId val id: String, // 用户id userDocument().id
  val level: Int = 5,
  val students: List<Int> = listOf(), // 来访的学生
  val touchedStudents: List<Int> = listOf(), // 能摸的学生, 为什么不是摸过的学生呢, 因为有些来的学生你没有啊
  val lastTouchTime: String = "2000-11-04 05:14:00", // 摸头计时器 每3小时刷新一次
  val lastInviteTime: String = "2000-11-04 05:14:00", // 上次邀请时间, 冷却时间23小时
  val lastStudentUpdateTime: String = "2000-11-04 05:14:00", // 上次刷新来访的时间, 每个整3点刷新
  val lastRewordCollectTime: String = "2000-11-04 05:14:00", // 上次领体力和信用点的时间
) {
  suspend fun updateStudents(students: List<Int>) {
    updateStudents0(CoffeeDocument::students.name, students)
    updateStudents0(CoffeeDocument::touchedStudents.name, students)
    updateTime(CoffeeDocument::lastStudentUpdateTime.name, currentDateTime())
  }

  suspend fun updateTouchedStudents(students: List<Int>, updateTouchTime: Boolean) {
    updateStudents0(CoffeeDocument::touchedStudents.name, students)
    if (updateTouchTime) {
      updateTime(CoffeeDocument::lastTouchTime.name, currentDateTime())
    }
  }

  suspend fun updateTime(name: String, value: String) {
    withCollection<CoffeeDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(name, value)
      )
    }
  }

  private suspend fun updateStudents0(name: String, students: List<Int>) {
    withCollection<CoffeeDocument, UpdateResult> {
      updateOne(
        filter = idFilter(id),
        update = Updates.set(name, students)
      )
    }
  }

  companion object : DocumentCompanionObject {
    override val documentName = "CoffeeTouch"
  }
}

@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
object CoffeeTouchCommand : AbstractCommand(
  Kivotos,
  "摸头",
  description = "咖啡厅摸头指令",
  help = """
      /赛博基沃托斯 咖啡厅 摸头 日奈
    """.trimIndent()
) {
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  private val visitedStudents by requireObject<List<StudentSchema>>()
  private val touchedStudents by requireObject<List<StudentSchema>>()
  private val coffee by requireObject<CoffeeDocument>()
  private val studentName by argument("学生名")
  suspend fun UserCommandSender.coffeeTouch() {
    val targetStudent = DatabaseProvider.dbQuery {
      StudentSchema.find { StudentTable.name eq studentName }.firstOrNull()
    }
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
    val md = md + if (targetStudent.id.value in coffee.touchedStudents) {
      tencentCustomMarkdown {
        +"你摸了摸$studentName, 好感+3"
        at()
      }
    } else {
      tencentCustomMarkdown {
        +"你摸了摸$studentName, 她绷不住了"
        at()
      }
    }
    sendMessage(md + kb)
    coffee.updateTouchedStudents(coffee.touchedStudents.toMutableList().also {
      it.remove(targetStudent.id.value)
    }, coffee.touchedStudents.size == coffee.students.size)
  }
}

@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
object CoffeeTouchAllCommand : AbstractCommand(
  Kivotos,
  "一键摸头",
  description = "咖啡厅一键摸头指令",
) {
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  private val visitedStudents by requireObject<List<StudentSchema>>()
  private val touchedStudents by requireObject<List<StudentSchema>>()
  private val coffee by requireObject<CoffeeDocument>()
  suspend fun UserCommandSender.coffeeTouch() {
    md append tencentCustomMarkdown {
      +"你分别摸了摸"
      list {
        visitedStudents.forEach {
          +it.name
        }
      }
    }
    md append tencentCustomMarkdown {
      touchedStudents.forEach {
        +"${it.name}的好感+3"
      }
    }
    md append tencentCustomMarkdown {
      visitedStudents.filter { it.id.value !in coffee.touchedStudents }.forEach {
        +"${it.name}绷不住了"
      }
      at()
    }
    sendMessage(md)
    coffee.updateTouchedStudents(listOf(), coffee.touchedStudents.size == coffee.students.size)
  }
}

private fun buildTouchButton(openid: String, students: List<String>): TencentCustomKeyboard {
  return tencentCustomKeyboard(openid) {
    (students + listOf("一键摸头")).windowed(2, 2, true)
      .forEach { r ->
        row {
          r.forEach { c ->
            if (c == "一键摸头") {
              subButton(c, "咖啡厅 一键摸头", true)
            } else {
              subButton("摸摸$c", "咖啡厅 摸头 $c", true)
            }
          }
        }
      }
  }
}
