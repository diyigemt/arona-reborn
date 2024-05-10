@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.config.CustomPool
import com.diyigemt.arona.arona.config.GachaConfig
import com.diyigemt.arona.arona.config.GachaPickupRate
import com.diyigemt.arona.arona.config.GachaRate
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.gacha.GachaPool
import com.diyigemt.arona.arona.database.gacha.GachaPoolSchema
import com.diyigemt.arona.arona.database.gacha.GachaPoolTable
import com.diyigemt.arona.arona.database.student.StudentLimitType
import com.diyigemt.arona.arona.database.student.StudentRarity
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.arona.tools.GachaResult
import com.diyigemt.arona.arona.tools.GachaResultItem
import com.diyigemt.arona.arona.tools.GachaTool
import com.diyigemt.arona.arona.tools.GachaTool.GachaResourcePath
import com.diyigemt.arona.arona.tools.GachaTool.NormalRStudent
import com.diyigemt.arona.arona.tools.GachaTool.NormalSRStudent
import com.diyigemt.arona.arona.tools.GachaTool.NormalSSRStudent
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BaseConfig
import com.diyigemt.arona.command.BuildInCommandOwner
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.updateContactPluginConfig
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.updateUserPluginConfig
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.console.confirm
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.utils.currentLocalDateTime
import com.diyigemt.arona.utils.runSuspend
import com.diyigemt.arona.utils.uuid
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.ConversionResult
import kotlinx.coroutines.delay
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

@Serializable
data class UserGachaRecordItem(
  @EncodeDefault
  var point: Int = 0,
  @EncodeDefault
  var ssr: Int = 0,
  @EncodeDefault
  var pickup: Int = 0,
)

@Serializable
data class UserGachaRecord(
  val map: MutableMap<Int, UserGachaRecordItem> = mutableMapOf(),
) : PluginWebuiConfig() {
  override fun check() {}
}

@Serializable
data class ContactGachaLimitItem(
  var day: Int = 0,
  var count: Int = 0,
)

/**
 * 用户 -> 卡池id -> 记录
 *
 * 特别的 -1 代表用户自定义或群主自定义的卡池
 */
@Serializable
data class ContactGachaLimitRecord(
  @EncodeDefault
  val map: MutableMap<String, MutableMap<Int, ContactGachaLimitItem>> = mutableMapOf(),
) : PluginWebuiConfig() {
  override fun check() {}
}

@Serializable
data class ContactGachaConfig(
  @EncodeDefault
  val limit: Int = 0,
) : PluginWebuiConfig() {
  override fun check() {}
}

private const val GachaPoolBridgeRedisKey = "com.diyigemt.arona.gacha.bridge"
private fun generateNumber(): String = (1..6).map { "0123456789".random() }.joinToString("")

@Suppress("unused")
class GachaCommand : AbstractCommand(
  Arona,
  "十连",
  description = "抽一发十连",
  help = tencentCustomMarkdown {
    list {
      +"不提供参数时, 抽日服当前最新池子"
      +"/十连 历史 查看当期池子记录"
      +"/十连 池子名称, 抽自己定义或当前环境管理员定义的池子"
    }
    +"自定义池子可看 [webui帮助](https://doc.arona.diyigemt.com/v2/manual/webui)"
  }.content
) {
  private val targetPoolName by argument(name = "要抽的池子", help = "对指定的池子抽一次十连").optional()
  private val bridge by option("-b", "--bridge", help = "桥接用户代码")

  suspend fun UserCommandSender.gacha() {
    if (!targetPoolName.isNullOrBlank()) {
      // 查看官方池子历史记录
      if (targetPoolName == "历史") {
        gachaHistory(0)
        return
      }
      suspend fun sendPoolChoice(poolConfig: List<CustomPool>, tip: String) {
        val md = tencentCustomMarkdown {
          h1("卡池选择")
          +tip
          +"仅能抽取群管理员预定义的和自己配置的"
        }
        val kb = tencentCustomKeyboard {
          poolConfig.windowed(2, 2, true).forEach { r ->
            row {
              r.forEach { c ->
                button(c.name, "/十连 ${c.name}")
              }
            }
          }
        }
        sendMessage(md + kb)
      }
      val contactConfig = contactDocument().readPluginConfigOrDefault(Arona, GachaConfig())
      val userConfig = userDocument().readPluginConfigOrDefault(Arona, GachaConfig()).pools
      val selfConfig = contactConfig.pools + userConfig
      // 抽指定的卡池
      val bridgeConfig: List<CustomPool> = if (bridge != null) {
        val key = "$GachaPoolBridgeRedisKey.$bridge"
        redis {
          get(key)
        }?.let {
          JsonIgnoreUnknownKeys.decodeFromString<List<CustomPool>>(it)
        } ?: return sendPoolChoice(selfConfig, "临时代码已过期")
      } else {
        listOf()
      }

      val poolConfig = selfConfig + bridgeConfig
      val pool = poolConfig.firstOrNull { it.name == targetPoolName }
      if (pool == null) {
        if (readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown.enable) {
          return sendPoolChoice(poolConfig, "池子不存在!")
        } else {
          sendMessage("池子不存在!, 可选:\n${poolConfig.joinToString("\n") { it.name }}")
        }
        return
      }
      // 开抽!
      if (checkGachaLimitOver(-1)) {
        return
      }
      val result = doGacha(pool.toGachaPool(), pool.rate, pool.pickupRate)
      // 如果直接通过桥接抽卡并且抽的不是自己的池子, 则不生成二次桥接
      return if (bridge != null && bridgeConfig.any { it.name == targetPoolName }) {
        sendResult(pool.name, 10, result, bridge)
      } else {
        // 如果通过桥接抽卡但是抽的是自己的池子, 或者没有通过桥接抽, 则生成新的桥接
        val code = generateNumber()
        val key = "$GachaPoolBridgeRedisKey.$code"
        redis {
          set(
            key,
            JsonIgnoreUnknownKeys.encodeToString(selfConfig)
          )
          expire(key, (10u * 60u).toULong())
        }
        sendResult(pool.name, 10, result, code)
      }
    }

    val pickupPool = GachaTool.CurrentPickupPool
    val pickupPoolId = pickupPool?.id?.value ?: 1
    // 检查是否超过抽卡限制次数
    if (checkGachaLimitOver(pickupPoolId)) {
      return
    }
    // 我的回合 抽卡!
    val pool = pickupPool?.toGachaPool() ?: GachaTool.NormalPool
    val result = doGacha(pool)
    // 保存抽卡记录
    val recordMap = readUserPluginConfigOrDefault(Arona, UserGachaRecord())
    val poolId = pickupPool?.id?.value ?: 1
    val record = recordMap.map[poolId] ?: UserGachaRecordItem()
    record.point += 10
    record.ssr += result.filter { it.rarity == StudentRarity.SSR }.size
    record.pickup += result.filter { it.isPickup }.size
    recordMap.map[poolId] = record
    updateUserPluginConfig(Arona, recordMap)

    // 发送图片
    sendResult(pool.name, record.point, result, null)
  }

  private suspend fun UserCommandSender.sendResult(
    poolName: String,
    point: Int,
    result: List<GachaResultItem>,
    bridgeCode: String?,
  ) {
    val randomFileName = "${uuid()}.jpg"
    val randomFile = Arona.dataFolder("gacha_result", randomFileName).toFile()
    GachaTool.generateGachaImage(
      GachaResult(
        poolName,
        point,
        result
      )
    ).also {
      it.makeImageSnapshot().encodeToData(format = EncodedImageFormat.PNG)?.bytes?.also { arr ->
        ByteArrayInputStream(arr).use { input ->
          Thumbnails
            .of(input)
            .scale(1.0)
            .outputQuality(0.6)
            .outputFormat("jpg")
            .toFile(randomFile)
        }
      }
    }
    val mdConfig = readUserPluginConfigOrDefault(BuildInCommandOwner, default = BaseConfig()).markdown
    if (mdConfig.enable) {
      val md = tencentCustomMarkdown {
        h1(poolName)
        image {
          href = "https://arona.diyigemt.com/image/gacha_result/$randomFileName"
          w = 2340
          h = 1080
        }
        at()
      }
      val kb = tencentCustomKeyboard {
        row {
          button(1) {
            render {
              label = "再来一次"
            }
            action {
              data = "/十连 " + if (bridgeCode != null) {
                "$poolName -b $bridgeCode"
              } else {
                ""
              }
              enter = true
            }
          }
          if (bridgeCode == null) {
            button(2) {
              render {
                label = "查看历史记录"
              }
              action {
                data = "/十连 历史"
                enter = true
              }
            }
          }
        }
      }
      MessageChainBuilder().append(md).append(kb).also { sendMessage(it.build()) }
    } else {
      subject.uploadImage("https://arona.diyigemt.com/image/gacha_result/$randomFileName").also {
        sendMessage(it)
      }
    }
    runSuspend {
      delay(30000)
      randomFile.delete()
    }
  }

  private fun doGacha(
    pool: GachaPool,
    rate: GachaRate = GachaRate(),
    pickupRate: GachaPickupRate = GachaPickupRate(),
  ): List<GachaResultItem> {
    val pickupList = pool.students.map { it.id.value }
    val pickupStudentList = pool.students
    val pickupSRStudentList = pickupStudentList.filter { it.rarity == StudentRarity.SR }
    val pickupSSRStudentList = pickupStudentList.filter { it.rarity == StudentRarity.SSR }

    var rRate = (rate.r * 10).toInt()
    val srRate = (rate.sr * 10).toInt()
    var ssrRate = (rate.ssr * 10).toInt()
    if (pool.isFes) {
      rRate -= 30
      ssrRate += 30
    }
    val result = List(10) { (0..1000).random() }.map {
      when (it) {
        in 0 until rRate -> {
          NormalRStudent
        }

        in rRate until rRate + srRate -> {
          if (it > (rRate + srRate) - (pickupRate.sr * 10).toInt()) {
            NormalSRStudent.takeIf { pickupSRStudentList.isEmpty() } ?: pickupSRStudentList
          } else {
            NormalSRStudent
          }
        }

        else -> {
          if (it > 1000 - (pickupRate.ssr * 10).toInt()) {
            NormalSSRStudent.takeIf { pickupSSRStudentList.isEmpty() } ?: pickupSSRStudentList
          } else {
            NormalSSRStudent
          }
        }
      }
    }.map {
      it.random()
    }.map {
      GachaResultItem(it.headFileName, isNew = false, isPickup = pickupList.contains(it.id.value), rarity = it.rarity)
    }.toMutableList()

    // 保底
    if (result.all { it.rarity == StudentRarity.R }) {
      (NormalSRStudent + pickupSRStudentList).random().also {
        result[9] = GachaResultItem(
          it.headFileName,
          isNew = false,
          isPickup = pickupList.contains(it.id.value),
          rarity = it.rarity
        )
      }
    }

    // 更新isNew
    result.forEachIndexed { idx, it ->
      it.isNew = it.isPickup && result.indexOfFirst { l -> l.image == it.image } == idx
    }
    return result
  }

  @Suppress("unused_parameter")
  private suspend fun UserCommandSender.gachaHistory(reserve: Int) {
    val recordMap = readUserPluginConfigOrDefault(Arona, UserGachaRecord())
    val poolId = GachaTool.CurrentPickupPool?.id?.value ?: 1
    val record = recordMap.map[poolId] ?: UserGachaRecordItem()
    val poolName = GachaTool.CurrentPickupPool?.name ?: "常驻池"
    if (record.point == 0) {
      sendMessage("当前池子: $poolName\n无抽卡记录")
    } else {
      MessageChainBuilder()
        .append("当前池子: $poolName")
        .append("招募点数: ${record.point}")
        .append("pickup: ${record.pickup}")
        .append("出彩数: ${record.ssr}")
        .append("出彩率: " + String.format("%.2f", record.ssr.toDouble() / record.point * 100) + "%")
        .build().also {
          sendMessage(it)
        }
    }
  }

  /**
   * 检查抽卡次数是否达到上限
   *
   * @param type 要检查的池子, 为-1表示为用户或群主自定义的池子
   *
   * @return true 达到上限 false 未达到上限
   */
  private suspend fun UserCommandSender.checkGachaLimitOver(type: Int): Boolean {
    // 获取管理设定的抽卡次数配置
    val contactConfig = readPluginConfigOrDefault(Arona, ContactGachaConfig())
    // 获取群自身保存的抽卡次数记录
    val contactRecord = contactDocument().readPluginConfigOrDefault(Arona, ContactGachaLimitRecord())
    // 获取群员自己的抽卡次数记录
    val userRecordMap = contactRecord.map.getOrDefault(user.id, mutableMapOf())
    val userRecord = userRecordMap.getOrDefault(type, ContactGachaLimitItem())
    // 检查次数
    val today = currentLocalDateTime().date.dayOfMonth
    if (type != 1 && contactConfig.limit > 0 && today == userRecord.day && contactConfig.limit <= userRecord.count) {
      sendMessage("今日已在本群抽卡${userRecord.count}次, 超过本群设置的${contactConfig.limit}上限")
      return true
    }
    // 未超过次数, 次数自增并保存
    userRecord.count += if (today == userRecord.day) 10 else 10 - userRecord.count
    userRecord.day = today
    userRecordMap[type] = userRecord
    contactRecord.map[user.id] = userRecordMap
    updateContactPluginConfig(Arona, contactRecord)
    return false
  }

}

@Suppress("unused")
class StudentConsoleCommand : CommandLineSubCommand, CliktCommand(name = "student", help = "学生管理cli") {
  private class UpdateStudent : CliktCommand(name = "i", help = "更新一个学生信息") {
    override fun run() {
      val studentName = terminal.prompt("请输入学生名称", null) as String
      val schema = dbQuery { StudentSchema.find { StudentTable.name eq studentName }.firstOrNull() }?.also {
        echo("将更新学生信息: $this")
      }
      val name = terminal.prompt("请输入学生名称", default = studentName) as String
      val limit =
        terminal.prompt("请输入学生限定类型", default = StudentLimitType.Permanent, choices = StudentLimitType
          .entries.map { it }) { input ->
          return@prompt StudentLimitType.entries.firstOrNull { it.name.lowercase() == input }?.run {
            ConversionResult.Valid(this)
          } ?: ConversionResult.Invalid("只能选这几个")
        } as StudentLimitType
      val rarity =
        terminal.prompt("请输入学生稀有度", default = StudentRarity.SSR, choices = StudentRarity
          .entries.map { it }) { input ->
          return@prompt StudentRarity.entries.firstOrNull { it.name.lowercase() == input }?.run {
            ConversionResult.Valid(this)
          } ?: ConversionResult.Invalid("只能选这几个")
        } as StudentRarity
      var head = terminal.prompt("请输入学生头图文件名", schema?.headFileName ?: "") as String
      while (!Files.exists(GachaResourcePath.resolve(head))) {
        echo("文件不存在")
        head = terminal.prompt("请输入学生头图文件名", null) as String
      }
      if (terminal.confirm(
          "将${if (schema == null) "新建" else "更新"}信息: name=$name, limit=$limit, rarity=$rarity, " +
              "head=$head"
        )
      ) {
        if (schema == null) {
          val create = dbQuery {
            StudentSchema.new {
              this@new.name = name
              this@new.limit = limit
              this@new.rarity = rarity
              this@new.headFileName = head
            }
          }
          dbQuery {
            GachaPoolSchema.find { GachaPoolTable.id eq 1 }.toList().first().also {
              it.students += listOf(create.id.value)
            }
          }
        } else {
          dbQuery {
            schema.name = name
            schema.limit = limit
            schema.rarity = rarity
            schema.headFileName = head
          }
        }
        echo("成功")
        StudentSchema.updateStudentCache()
      } else {
        echo("取消更新")
      }
    }

  }

  private class DeleteStudent : CliktCommand(name = "d", help = "删除一个学生信息") {
    override fun run() {
      val schema = studentSelector(this)
      if (terminal.confirm("将删除学生信息: $schema")) {
        dbQuery {
          schema.delete()
        }
      } else {
        echo("操作取消")
      }
    }
  }

  private class QueryStudent : CliktCommand(name = "q", help = "查询学生信息") {
    override fun run() {
      val studentName = terminal.prompt("请输入学生名称", null) as String
      dbQuery {
        StudentSchema
          .find { StudentTable.name like "%$studentName%" }
          .toList()
      }
        .forEach {
          echo(it)
        }
    }
  }

  init {
    subcommands(UpdateStudent(), DeleteStudent(), QueryStudent())
  }

  override fun run() {

  }
}

@Suppress("unused")
class GachaConsoleCommand : CommandLineSubCommand, CliktCommand(
  name = "gacha", help = "抽卡管理cli",
  invokeWithoutSubcommand = true
) {
  private class QueryPool : CliktCommand(name = "q", help = "查询卡池信息") {
    override fun run() {
      var poolName: String? = null
      while (poolName.isNullOrBlank()) {
        poolName = terminal.prompt("请输入卡池名称", null) as String
      }
      val poolList = dbQuery {
        GachaPoolSchema
          .find { GachaPoolTable.name like "%$poolName%" }
          .toList()
      }
      echo(poolList
        .mapIndexed { idx, it -> "$idx. ${it.name}(active=${it.active})" }
      )
      val idx =
        terminal.prompt("请输入卡池编号", default = 0, choices = List(poolList.size) { index -> index }) { input ->
          ConversionResult.Valid(input.toIntOrNull())
        } as Int
      echo(poolList[idx])
    }
  }

  private class CreatePool : CliktCommand(name = "c", help = "创建卡池") {
    override fun run() {
      val poolName = terminal.prompt("请输入卡池名称", null) as String
      val fes = terminal.confirm("fes", default = "N")
      if (dbQuery { GachaPoolSchema.find { GachaPoolTable.name eq poolName }.toList().firstOrNull() } != null) {
        echo("卡池名称存在", err = true)
        return
      }
      if (terminal.confirm("创建卡池")) {
        dbQuery {
          GachaPoolSchema.new {
            name = poolName
            active = false
            students = listOf()
            this@new.fes = fes
          }
        }
      }
    }
  }

  private class ActivePool : CliktCommand(name = "a", help = "激活卡池") {
    override fun run() {
      val pool = poolSelector(this)
      if (terminal.confirm("激活卡池")) {
        GachaPoolSchema.activePool(pool.id.value)
      }
    }
  }

  private class DeletePool : CliktCommand(name = "d", help = "删除卡池") {
    override fun run() {
      val pool = poolSelector(this)
      echo(pool.toString())
      if (pool.active) {
        echo("删除当前卡池将自动激活上一个卡池")
      }
      if (terminal.confirm("删除卡池")) {
        val prv = dbQuery {
          pool.delete()
          GachaPoolSchema.all().orderBy(GachaPoolTable.id to SortOrder.DESC).limit(1).toList().first()
        }
        GachaPoolSchema.activePool(prv.id.value)
        echo("已激活:\n$prv")
      }
    }
  }

  private class UpdatePool : CliktCommand(name = "u", help = "更新卡池信息") {
    override fun run() {
      val poolName = terminal.prompt("请输入卡池名称", null)
      val pool = dbQuery {
        if (poolName.isNullOrBlank()) {
          GachaPoolSchema.all().orderBy(GachaPoolTable.id to SortOrder.DESC).limit(1).firstOrNull()
        } else {
          GachaPoolSchema.find { GachaPoolTable.name like "%$poolName%" }.firstOrNull()
        }
      }
      if (pool == null) {
        echo("没找到对应的卡池", err = true)
        return
      }
      echo("卡池: $pool")
      val type = terminal.prompt(
        "请输入更改类型编号(0改pickup1改名)",
        default = 0,
        choices = listOf(0, 1)
      ) { input ->
        ConversionResult.Valid(input.toIntOrNull())
      } as Int
      when (type) {
        1 -> {
          val name = terminal.prompt("请输入卡池名", default = pool.name) as String
          val fes = terminal.confirm("fes", default = "N")
          if (terminal.confirm("新名: $name, fes: $fes")) {
            dbQuery {
              pool.name = name
              pool.fes = fes
            }
          }
        }

        0 -> {
          val editType = terminal.prompt("请输入更改类型编号(0增1删)", default = 0, choices = listOf(0, 1)) { input ->
            ConversionResult.Valid(input.toIntOrNull())
          } as Int
          when (editType) {
            0 -> {
              val student = studentSelector(this)
              if (terminal.confirm("添加学生: $student")) {
                dbQuery {
                  pool.students += listOf(student.id.value)
                }
              }
            }

            1 -> {
              if (pool.students.isEmpty()) {
                echo("空的学生列表", err = true)
                return
              }
              val s = pool.toGachaPool().students
              echo(s.mapIndexed { idx, it -> "$idx. $it" }.joinToString("\n"))
              val select = terminal.prompt(
                "请选择",
                default = 0,
                choices = List(s.size) {
                    index,
                  ->
                  index
                }) { input ->
                ConversionResult.Valid(input.toIntOrNull())
              } as Int
              if (terminal.confirm("删除学生: ${s[select]}")) {
                dbQuery {
                  pool.students = pool.students.toMutableList().also {
                    it.remove(s[select].id.value)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  init {
    subcommands(QueryPool(), CreatePool(), UpdatePool(), ActivePool(), DeletePool())
  }

  override fun run() {
    val subcommand = currentContext.invokedSubcommand
    if (subcommand == null) {
      dbQuery {
        val pool = GachaPoolSchema
          .find { GachaPoolTable.active eq true }
          .toList()
          .firstOrNull() ?: GachaPoolSchema.findById(1) as GachaPoolSchema
        echo(pool.toString())
      }
    }
  }
}

fun poolSelector(command: CliktCommand): GachaPoolSchema {
  return command.run {
    var pool: GachaPoolSchema? = null
    while (pool == null) {
      val poolName = terminal.prompt("请输入卡池名称", null) as String
      val poolList = dbQuery {
        GachaPoolSchema
          .find { GachaPoolTable.name like "%$poolName%" }
          .toList()
      }
        .onEach { p ->
          echo(p)
        }
      val idx =
        terminal.prompt(
          "请输入卡池编号",
          default = 0,
          choices = List(poolList.size) { index -> index } + listOf(-1)) { input ->
          ConversionResult.Valid(input.toIntOrNull())
        } as Int
      if (idx != -1) {
        pool = poolList[idx]
      }
    }
    pool
  }
}

fun studentSelector(command: CliktCommand): StudentSchema {
  return command.run {
    var student: StudentSchema? = null
    while (student == null) {
      val studentName = terminal.prompt("请输入学生名称", null) as String
      val studentList = dbQuery {
        StudentSchema
          .find { StudentTable.name like "%$studentName%" }
          .toList()
      }
        .also {
          echo(it)
        }
      val idx =
        terminal.prompt(
          "请输入学生编号",
          default = 0,
          choices = List(studentList.size) { index -> index } + listOf(-1)) { input ->
          ConversionResult.Valid(input.toIntOrNull())
        } as Int
      if (idx != -1) {
        student = studentList[idx]
      }
    }
    student
  }
}
