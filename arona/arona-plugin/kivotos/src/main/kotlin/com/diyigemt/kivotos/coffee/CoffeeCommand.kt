package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.arona.command.ImageQueryData
import com.diyigemt.arona.arona.database.DatabaseProvider
import com.diyigemt.arona.arona.database.image.ImageCacheTable.hash
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.utils.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.schema.ErrorDocument
import com.diyigemt.kivotos.schema.UserDocument
import com.diyigemt.kivotos.subButton
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.codecs.pojo.annotations.BsonId


private suspend fun UserCommandSender.coffee() = CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument?> {
  find(filter = idFilter(userDocument().id)).limit(1).firstOrNull()
} ?: CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument> {
  CoffeeDocument(userDocument().id).also {
    insertOne(it)
  }
}

private const val MORNING_TIME = "03:00:00"
private const val AFTERNOON_TIME = "15:00:00"

private fun calcNextTouchTime(coffee: CoffeeDocument): String {
  val last = coffee.lastTouchTime.toInstant().toTime()
  val next = coffee.lastTouchTime.toInstant().plus(3, DateTimeUnit.HOUR).toTime()
  return if ((last < MORNING_TIME) and (next > MORNING_TIME)) {
    MORNING_TIME
  } else if ((last < AFTERNOON_TIME) and (next > AFTERNOON_TIME)) {
    AFTERNOON_TIME
  } else {
    next
  }
}

@SubCommand(forClass = KivotosCommand::class)
@Suppress("unused")
object CoffeeCommand : AbstractCommand(
  Kivotos,
  "咖啡厅",
  description = "咖啡厅系列指令",
  help = tencentCustomMarkdown {
    list {
      +"/赛博基沃托斯 咖啡厅 摸头 白子, 摸摸白子"
      +"/赛博基沃托斯 咖啡厅 一键摸头, 全摸了"
      +"/赛博基沃托斯 咖啡厅 邀请 白子, 邀请白子来咖啡厅"
    }
  }.content
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
      visitedStudents.map { it.name },
      coffee = coffee
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
  val level: Int = 12,
  val students: List<Int> = listOf(), // 来访的学生
  val touchedStudents: List<Int> = listOf(), // 能摸的学生, 为什么不是摸过的学生呢, 因为有些来的学生你没有啊
  val lastTouchTime: String = "2000-11-04 05:14:00", // 摸头计时器 每3小时刷新一次
  val nextInviteTime: String = "2000-11-04 05:14:00", // 下次能邀请的时间, 冷却时间23小时
  val lastStudentUpdateTime: String = "2000-11-04 05:14:00", // 上次刷新来访的时间, 每个整3点刷新
  val lastRewordCollectTime: String = "2000-11-04 05:14:00", // 上次领体力和信用点的时间
  val lastInviteStudent: String = "白子", // 上次邀请的学生
) {
  /**
   * 是否能邀请学生
   */
  val canInviteStudent
    get() = currentDateTime() >= nextInviteTime

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

  suspend fun updateStudents0(name: String, students: List<Int>) {
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
      /${KivotosCommand.primaryName} 咖啡厅 摸头 日奈
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
      val updates = kivotosUser.updateStudentFavor(targetStudent.id.value, 15)
      tencentCustomMarkdown {
        +"你摸了摸$studentName, 好感+15 (${updates.third})"
        if (updates.first) {
          +"$studentName 的好感上升了, 当前等级: ${updates.second}"
        }
        at()
      }
    } else {
      tencentCustomMarkdown {
        +"你摸了摸$studentName, 她绷不住了"
        +"下次摸头刷新时间: ${calcNextTouchTime(coffee)}"
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
  private val kivotosUser by requireObject<UserDocument>()
  suspend fun UserCommandSender.coffeeTouch() {
    md append tencentCustomMarkdown {
      +"你分别摸了摸学生们"
    }
    touchedStudents.forEach {
      kivotosUser.updateStudentFavor(it.id.value, 15).also { p ->
        md append tencentCustomMarkdown {
          if (p.first) {
            +"${it.name}的好感+15, 好感等级上升了, 当前等级: ${p.second}"
          } else {
            +"${it.name}的好感+15 (${p.third})"
          }
        }
      }
    }
    md append tencentCustomMarkdown {
      visitedStudents.filter { it.id.value !in coffee.touchedStudents }.forEach {
        +"${it.name}绷不住了"
      }
    }
    if ("绷不住了" in md.content) {
      md append tencentCustomMarkdown {
        +"下次摸头刷新时间: ${calcNextTouchTime(coffee)}"
      }
    }
    md append tencentCustomMarkdown {
      at()
    }
    sendMessage(md)
    coffee.updateTouchedStudents(listOf(), coffee.touchedStudents.size == coffee.students.size)
  }
}

@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
object CoffeeInviteCommand : AbstractCommand(
  Kivotos,
  "邀请",
  description = "咖啡厅邀请指令",
  help = """
      /${KivotosCommand.primaryName} 咖啡厅 邀请 日奈
    """.trimIndent()
) {
  private val json = Json {
    ignoreUnknownKeys = true
  }
  private val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
      json
    }
  }
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  private val coffee by requireObject<CoffeeDocument>()
  private val visitedStudents by requireObject<List<StudentSchema>>()
  private val touchedStudents by requireObject<List<StudentSchema>>()
  private val kivotosUser by requireObject<UserDocument>()
  private val studentName by argument("学生名").optional()
  suspend fun UserCommandSender.invite() {
    if (!coffee.canInviteStudent) {
      md append tencentCustomMarkdown {
        at()
        +"还没到邀请刷新时间, 下次刷新: ${coffee.nextInviteTime}"
      }
      sendMessage(md)
      return
    }
    if (studentName == null) {
      val message = tencentCustomMarkdown {
        at()
        +"请输入学生名, 如"
        +"/${KivotosCommand.primaryName} 咖啡厅 邀请 日奈"
      } + tencentCustomKeyboard {
        row {
          subButton("邀请学生", "/${KivotosCommand.primaryName} 咖啡厅 邀请 ")
        }
      }
      sendMessage(message)
      return
    }
    val sn = studentName!!
    // 尝试在本地数据库查找
    val student = DatabaseProvider.dbQuery {
      StudentSchema.find {
        StudentTable.name eq sn
      }.firstOrNull()
    }?.name ?: // 没找到 尝试在远端找
    httpClient.get("https://arona.diyigemt.com/api/v2/image") {
      parameter("name", sn)
    }.let {
      return@let kotlin.runCatching {
        if (it.status == HttpStatusCode.OK) {
          val tmp = it.bodyAsText().let {
            json.decodeFromString<ServerResponse<List<ImageQueryData>>>(it)
          }
          return@runCatching if (tmp.code == HttpStatusCode.OK) {
            tmp.data?.firstOrNull()?.content?.substringAfterLast("/")?.substringBefore(".")
          } else {
            null
          }
        } else {
          null
        }
      }.getOrNull()
    }
    if (student == null) {
      val message = tencentCustomMarkdown {
        at()
        +"没有找到该学生, 试着提供其他别名"
      } + tencentCustomKeyboard {
        row {
          subButton("邀请学生", "咖啡厅 邀请 ")
        }
      }
      sendMessage(message)
      return
    }
    // 找到学生

    // 开始邀请
    val target = DatabaseProvider.dbQuery {
      StudentSchema.find {
        StudentTable.name eq student
      }.firstOrNull()
    }

    if (target == null) {
      sendMessage("错误: 邀请学生不存在, 目标: $student 建议上报")
      ErrorDocument.createError(404, "错误: 邀请学生不存在, 目标: $student")
      return
    }

    // 判断来没来
    if (target.id.value in coffee.students) {
      sendMessage("${target.name} 已经在咖啡厅里了")
      return
    }

    (tencentCustomMarkdown {
      h2("确认")
      at()
      +"确认邀请 $student 来咖啡厅吗?"
    } + tencentCustomKeyboard {
      row {
        button {
          render {
            label = "确认"
          }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = "Y"
          }
          selfOnly()
        }
        button {
          render {
            label = "取消"
          }
          action {
            type = TencentKeyboardButtonActionType.CALLBACK
            data = "N"
          }
          selfOnly()
        }
      }
    }).also {
      sendMessage(it)
    }
    val to = withTimeoutOrNull(10000L) {
      val next = nextButtonInteraction()
      next.accept()
      if (next.buttonData == "Y") {
        coffee.updateStudents0(CoffeeDocument::students.name, coffee.students + target.id.value)
        coffee.updateTouchedStudents(coffee.touchedStudents + target.id.value, false)
        coffee.updateTime(CoffeeDocument::nextInviteTime.name, now().plus(23, DateTimeUnit.HOUR).toDateTime())
        CommandManager.executeCommand(this@invite, PlainText("/${KivotosCommand.primaryName} 咖啡厅"))
      }
    }
    if (to == null) {
      sendMessage("操作超时")
    }
  }
}

infix fun <A, B, C> Pair<A, B>.to(other: C) = Triple(this.first, this.second, other)

private fun UserCommandSender.buildTouchButton(
  students: List<String>,
  coffee: CoffeeDocument
): TencentCustomKeyboard {
  return tencentCustomKeyboard {
    (students.map {
      "摸摸$it" to "咖啡厅 摸头 $it" to true
    } + listOf(
      "一键摸头" to "咖啡厅 一键摸头" to true
    ) + if (coffee.canInviteStudent) {
      listOf(
        "邀请${coffee.lastInviteStudent}" to "咖啡厅 邀请 ${coffee.lastInviteStudent}" to true,
        "邀请学生" to "咖啡厅 邀请" to false
      )
    } else {
      listOf()
    }).windowed(2, 2, true)
      .forEach { r ->
        row {
          r.forEach { c ->
            subButton(c.first, c.second, c.third)
          }
        }
      }
  }
}
