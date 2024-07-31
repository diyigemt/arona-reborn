@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.kivotos.coffee

import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.command.SubCommand
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis
import com.diyigemt.arona.utils.*
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.KivotosCommand
import com.diyigemt.kivotos.coffee.CoffeeTouchCommand.Companion
import com.diyigemt.kivotos.schema.ErrorDocument
import com.diyigemt.kivotos.schema.UserDocument
import com.diyigemt.kivotos.subButton
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.diyigemt.kivotos.tools.normalizeStudentName
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.github.crackthecodeabhi.kreds.args.SetOption
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.codecs.pojo.annotations.BsonId
import kotlin.math.min


private suspend fun UserCommandSender.coffee() = CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument?> {
  find(filter = idFilter(userDocument().id)).limit(1).firstOrNull()
} ?: CoffeeDocument.withCollection<CoffeeDocument, CoffeeDocument> {
  CoffeeDocument(userDocument().id).also {
    insertOne(it)
  }
}

private const val MORNING_TIME = "03:00:00"
private const val AFTERNOON_TIME = "15:00:00"

// 检查学生刷新时间
private fun checkStudentUpdate(last0: String): Boolean {
  val now = now()
  val nowDatetime = now.toDateTime()
  val last = last0.toInstant()
  val morning = "${currentDate()} $MORNING_TIME"
  val afternoon = "${currentDate()} $AFTERNOON_TIME"
  // 超过12小时 肯定需要更新
  return (now - last).inWholeHours >= 12L ||
    morning in last0.rangeUntil(nowDatetime) ||
    afternoon in last0.rangeUntil(nowDatetime)
}

// 检查学生摸头刷新时间 从第一次摸开始算起3小时刷新
private fun checkStudentTouchedUpdate(last0: String): Boolean {
  val now = now()
  val last = last0.toInstant()
  val duration = now - last
  return duration.inWholeHours >= 3
}

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
class CoffeeCommand : AbstractCommand(
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
      // 随机倾向名单 每个来访学生独立计算, 20%来一个倾向名单内的学生, min保证不会来多于倾向名单内的学生
      val tendencyStudentCount = min(
        (1..studentCount(coffee.level)).filter {
          (1..100).random() > 80
        }.size,
        coffee.tendencyStudent.size
      )
      val tendency = coffee.tendencyStudent.shuffled().take(tendencyStudentCount)
      val visit = StudentSchema.studentIdList().toMutableList().also {
        it.removeAll(tendency)
      }.shuffled().take(studentCount(coffee.level) - tendencyStudentCount)

      val students = StudentSchema.StudentCache.filter {
        it.key in (visit + tendency)
      }.values
      coffee.updateStudents(students.map { it.id.value })
      coffee = coffee()
    } else if (checkStudentTouchedUpdate(coffee.lastTouchTime)) {
      coffee.updateTouchedStudents(coffee.students, false)
      coffee = coffee()
    }
    currentContext.setObject("coffee", coffee)

    val visitedStudents = StudentSchema.StudentCache.filter {
      it.key in coffee.students
    }.values
    val touchedStudents = StudentSchema.StudentCache.filter {
      it.key in coffee.touchedStudents
    }.values
    currentContext.setObject("visitedStudents", visitedStudents.toList())
    currentContext.setObject("touchedStudents", touchedStudents.toList())
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
      kb append tencentCustomKeyboard {
        row {
          button("来访倾向管理", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向")
        }
      }
      kb.windowed()
      sendMessage(md + kb)
    }
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
  val tendencyStudent: List<Int> = listOf(), // 更容易来访的学生
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
class CoffeeTouchCommand : AbstractCommand(
  Kivotos,
  "摸头",
  description = "咖啡厅摸头指令",
  help = """
      /${KivotosCommand.primaryName} 咖啡厅 摸头 日奈
    """.trimIndent()
) {
  companion object {
    const val LOCK_PREFIX = "kivotos.coffee-touch-once"
  }
  private val md by requireObject<TencentCustomMarkdown>()
  private val kb by requireObject<TencentCustomKeyboard>()
  private val visitedStudents by requireObject<List<StudentSchema>>()
  private val touchedStudents by requireObject<List<StudentSchema>>()
  private val coffee by requireObject<CoffeeDocument>()
  private val kivotosUser by requireObject<UserDocument>()
  private val studentName by argument("学生名")
  suspend fun UserCommandSender.coffeeTouch() {
    val key = "$LOCK_PREFIX.${user.unionOpenidOrId}"
    redis {
      get(key)
    }?.run {
      sendMessage("摸太快啦, 学生的毛都要被你薅没了")
      return
    }
    redis {
      set(key, "1", SetOption.Builder(exSeconds = 3u).build())
    }
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
class CoffeeTouchAllCommand : AbstractCommand(
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
    val key = "${CoffeeTouchCommand.LOCK_PREFIX}.${user.unionOpenidOrId}"
    redis {
      get(key)
    }?.run {
      sendMessage("摸太快啦, 学生的毛都要被你薅没了")
      return
    }
    redis {
      set(key, "1", SetOption.Builder(exSeconds = 3u).build())
    }
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
    val kb = if (coffee.canInviteStudent) {
      tencentCustomKeyboard {
        row {
          subButton("邀请${coffee.lastInviteStudent}", "咖啡厅 邀请 ${coffee.lastInviteStudent}", enter = true)
          subButton("邀请学生", "咖啡厅 邀请 ${coffee.lastInviteStudent}", enter = false)
        }
        selfOnly()
      }
    } else PlainText("")
    sendMessage(md + kb)
    coffee.updateTouchedStudents(listOf(), coffee.touchedStudents.size == coffee.students.size)
  }
}

@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
class CoffeeInviteCommand : AbstractCommand(
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
          subButton("邀请学生", "咖啡厅 邀请 ${coffee.lastInviteStudent}")
        }
      }
      sendMessage(message)
      return
    }
    val sn = studentName!!
    // 尝试在本地数据库查找
    val student = normalizeStudentName(sn)
    if (student == null) {
      val message = tencentCustomMarkdown {
        +"没有找到该学生, 试着提供其他别名"
        at()
      } + tencentCustomKeyboard {
        row {
          subButton("邀请学生", "咖啡厅 邀请 ${coffee.lastInviteStudent}")
        }
      }
      sendMessage(message)
      return
    }
    // 找到学生

    // 开始邀请

    val target = StudentSchema.StudentCache.filter {
      it.value.name == student
    }.values.firstOrNull()

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
    val configure = readUserPluginConfigOrDefault(Kivotos, CoffeeConfig())
    if (configure.inviteDoubleCheck) {
      val message = (tencentCustomMarkdown {
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
      }).let {
        sendMessage(it)
      }
      val to = withTimeoutOrNull(10000L) {
        val next = nextButtonInteraction()
        next.accept()
        if (next.buttonData == "Y") {
          message?.recall()
          coffee.updateStudents0(CoffeeDocument::students.name, coffee.students + target.id.value)
          coffee.updateTouchedStudents(coffee.touchedStudents + target.id.value, false)
          coffee.updateTime(CoffeeDocument::nextInviteTime.name, now().plus(20, DateTimeUnit.HOUR).toDateTime())
          coffee.updateTime(CoffeeDocument::lastInviteStudent.name, student)
          if (configure.touchAfterInvite) {
            CommandManager.executeCommand(
              this@invite,
              PlainText("/${KivotosCommand.primaryName} 咖啡厅 摸头 $student")
            ).await()
          } else {
            CommandManager.executeCommand(
              this@invite,
              PlainText("/${KivotosCommand.primaryName} 咖啡厅")
            ).await()
          }
        }
      }
      if (to == null) {
        sendMessage("操作超时")
      }
    } else {
      coffee.updateTouchedStudents(coffee.touchedStudents + target.id.value, false)
      coffee.updateStudents0(CoffeeDocument::students.name, coffee.students + target.id.value)
      coffee.updateTime(CoffeeDocument::lastInviteStudent.name, student)
      coffee.updateTime(CoffeeDocument::nextInviteTime.name, now().plus(20, DateTimeUnit.HOUR).toDateTime())
      if (configure.touchAfterInvite) {
        CommandManager.executeCommand(
          this@invite,
          PlainText("/${KivotosCommand.primaryName} 咖啡厅 摸头 $student")
        ).await()
      } else {
        CommandManager.executeCommand(
          this@invite,
          PlainText("/${KivotosCommand.primaryName} 咖啡厅")
        ).await()
      }
    }
  }
}

@SubCommand(forClass = CoffeeCommand::class)
@Suppress("unused")
class CoffeeStudentTendencyCommand : AbstractCommand(
  Kivotos,
  "来访倾向",
  description = "咖啡厅来访倾向管理指令",
  help = """
      /${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 日奈
      /${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 日奈
    """.trimIndent()
) {
  private val coffee by requireObject<CoffeeDocument>()
  suspend fun UserCommandSender.tendency() {
    val tendencyStudents = StudentSchema.StudentCache.filter {
      it.key in coffee.tendencyStudent
    }.values
    currentContext.setObject("tendencyStudents", tendencyStudents.toList())
    val md = tencentCustomMarkdown {
      h2("来访倾向配置")
      block {
        +"因为放不了家具, 所以互动家具吸引学生的设定就手动配吧"
        +"最多配置4个, 被配置的学生将有独立的乘区更容易来咖啡厅"
      }
      +"目前已经配置的学生: ${if (coffee.tendencyStudent.isEmpty()) "空" else ""}"
      if (coffee.tendencyStudent.isNotEmpty()) {
        list {
          tendencyStudents.forEach {
            +it.name
          }
        }
      }
    }
    val kb = (tencentCustomKeyboard {
      tendencyStudents.forEach {
        row {
          button(
            "删除${it.name}", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 ${it.name}"
          )
        }
      }
    } + tencentCustomKeyboard {
      row {
        button(
          "添加学生", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 "
        )
      }
    }).also { it.windowed() }
    currentContext.setObject("kb", kb)
    currentContext.setObject("md", md)
    if (currentContext.invokedSubcommand == null) {
      md append tencentCustomMarkdown {
        at()
      }
      sendMessage(md + kb)
    }
  }

  @SubCommand
  @Suppress("unused")
  class CoffeeStudentTendencyAddCommand : AbstractCommand(
    Kivotos,
    "添加",
    description = "咖啡厅来访倾向添加指令",
    help = """
      /${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 日奈
    """.trimIndent()
  ) {
    private val coffee by requireObject<CoffeeDocument>()
    private val tendencyStudents by requireObject<List<StudentSchema>>()
    private val md by requireObject<TencentCustomMarkdown>()
    private val studentName by argument("学生名").optional()

    suspend fun UserCommandSender.tendencyAdd() {
      if (studentName == null) {
        md append tencentCustomMarkdown {
          +"缺少参数: 学生名称, 如"
          +"/${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 日奈"
          at()
        }
        val kb = tencentCustomKeyboard {
          row {
            button(
              "添加日奈", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 日奈"
            )
            button(
              "添加学生", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 添加 "
            )
          }
        }
        sendMessage(md + kb)
        return
      }
      if (coffee.tendencyStudent.size >= 4) {
        sendMessage("已经配置了4位学生, 没法再添加了")
        return
      }
      val sn = studentName!!
      val student = normalizeStudentName(sn)
      if (student == null) {
        val message = tencentCustomMarkdown {
          +"没有找到该学生, 试着提供其他别名"
          at()
        } + tencentCustomKeyboard {
          row {
            subButton(
              "添加学生", "咖啡厅 来访倾向 添加 $sn"
            )
          }
        }
        sendMessage(message)
        return
      }

      // 开始添加
      val target = StudentSchema.StudentCache.filter {
        it.value.name == student
      }.values.firstOrNull()

      if (target == null) {
        sendMessage("错误: 要添加的学生不存在, 目标: $student 建议上报")
        ErrorDocument.createError(404, "错误: 要添加的学生不存在, 目标: $student")
        return
      }

      coffee.updateStudents0(CoffeeDocument::tendencyStudent.name, coffee.tendencyStudent + target.id.value)
      sendMessage("${target.name} 添加成功")
    }
  }

  @SubCommand
  @Suppress("unused")
  class CoffeeStudentTendencyRemoveCommand : AbstractCommand(
    Kivotos,
    "删除",
    description = "咖啡厅来访倾向删除指令",
    help = """
      /${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 日奈
    """.trimIndent()
  ) {
    private val coffee by requireObject<CoffeeDocument>()
    private val tendencyStudents by requireObject<List<StudentSchema>>()
    private val md by requireObject<TencentCustomMarkdown>()
    private val studentName by argument("学生名").optional()

    suspend fun UserCommandSender.tendencyAdd() {
      if (studentName == null) {
        md append tencentCustomMarkdown {
          +"缺少参数: 学生名称, 如"
          +"/${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 日奈"
          at()
        }
        val kb = tencentCustomKeyboard {
          row {
            button(
              "删除日奈", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 日奈"
            )
            button(
              "删除学生", "/${KivotosCommand.primaryName} 咖啡厅 来访倾向 删除 "
            )
          }
        }
        sendMessage(md + kb)
        return
      }
      if (coffee.tendencyStudent.isEmpty()) {
        sendMessage("已经空了, 没法再删除了")
        return
      }
      val sn = studentName!!
      val student = normalizeStudentName(sn)
      if (student == null) {
        val message = tencentCustomMarkdown {
          +"没有找到该学生, 试着提供其他别名"
          at()
        } + tencentCustomKeyboard {
          row {
            subButton(
              "删除学生", "咖啡厅 来访倾向 删除 $sn"
            )
          }
        }
        sendMessage(message)
        return
      }

      // 开始删除
      val target = StudentSchema.StudentCache.filter {
        it.value.name == student
      }.values.firstOrNull()

      if (target == null) {
        sendMessage("错误: 要删除的学生不存在, 目标: $student 建议上报")
        ErrorDocument.createError(404, "错误: 要删除的学生不存在, 目标: $student")
        return
      }

      if (target.id.value !in coffee.tendencyStudent) {
        sendMessage("$student 不在倾向列表里")
        return
      }

      coffee.updateStudents0(
        CoffeeDocument::tendencyStudent.name,
        coffee.tendencyStudent
          .toMutableList()
          .also {
            it.remove(target.id.value)
          })
      sendMessage("${target.name} 删除成功")
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
        "邀请学生" to "咖啡厅 邀请 ${coffee.lastInviteStudent}" to false
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
  }.also {
    it.windowed()
  }
}
