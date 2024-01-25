package com.diyigemt.arona.arona.command

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.gacha.GachaPoolSchema
import com.diyigemt.arona.arona.database.gacha.GachaPoolTable
import com.diyigemt.arona.arona.database.student.StudentLimitType
import com.diyigemt.arona.arona.database.student.StudentRarity
import com.diyigemt.arona.arona.database.student.StudentSchema
import com.diyigemt.arona.arona.database.student.StudentTable
import com.diyigemt.arona.arona.tools.GachaResult
import com.diyigemt.arona.arona.tools.GachaResultItem
import com.diyigemt.arona.arona.tools.GachaTool
import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.readUserPluginConfigOrDefault
import com.diyigemt.arona.communication.command.UserCommandSender.Companion.updateUserPluginConfig
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.console.confirm
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.ConversionResult
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files

@Serializable
data class UserGachaRecordItem(
  var point: Int = 0,
  var ssr: Int = 0,
  var pickup: Int = 0
)
@Serializable
data class UserGachaRecord(
  val map: MutableMap<Int, UserGachaRecordItem>
)

@Suppress("unused")
object GachaCommand : AbstractCommand(
  Arona,
  "十连",
  description = "抽一发十连"
) {
  suspend fun UserCommandSender.gacha() {
    val pickupPool = GachaPoolSchema.currentActivePool()
    val pickupList = pickupPool?.students ?: listOf()
    val studentList = (pickupPool?.toGachaPool() ?: GachaTool.NormalPool).students + GachaTool.NormalPool.students
    val result = List(10) { studentList.random() }.map {
      GachaResultItem(it.headFileName, isNew = false, isPickup = pickupList.contains(it.id.value), rarity = it.rarity)
    }
    // 更新isNew
    result.forEachIndexed { idx, it ->
      it.isNew = result.indexOfFirst { l -> l.image == it.image } == idx
    }

    val recordMap = readUserPluginConfigOrDefault(Arona, UserGachaRecord(mutableMapOf()))
    val poolId = pickupPool?.id?.value ?: 1
    val record = recordMap.map[poolId] ?: UserGachaRecordItem()
    record.point += 10
    record.ssr += result.filter { it.rarity == StudentRarity.SSR }.size
    record.pickup += result.filter { it.isPickup }.size
    recordMap.map[poolId] = record
    updateUserPluginConfig(Arona, recordMap)

    GachaTool.generateGachaImage(
      GachaResult(
        pickupPool?.name ?: GachaTool.NormalPool.name,
        record.point,
        result
      )
    ).also {
      it.makeImageSnapshot().encodeToData(format = EncodedImageFormat.PNG)?.bytes?.also { im ->
        Files.write(Arona.dataFolderPath.resolve("gacha.png"), im)
      }
    }
  }

}

@Suppress("unused")
class StudentConsoleCommand : CommandLineSubCommand, CliktCommand(name = "student", help = "学生管理cli") {
  private class UpdateStudent : CliktCommand(name = "i", help = "更新一个学生信息") {
    override fun run() {
      dbQuery {
        val studentName = terminal.prompt("请输入学生名称", null) as String
        val schema = StudentSchema.find { StudentTable.name eq studentName }.firstOrNull()?.also {
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
        val head = terminal.prompt("请输入学生头图文件名", null) as String
        if (terminal.confirm(
            "将${if (schema == null) "新建" else "更新"}信息: name=$name, limit=$limit, rarity=$rarity, " +
                "head=$head"
          )
        ) {
          if (schema == null) {
            StudentSchema.new {
              this@new.name = name
              this@new.limit = limit
              this@new.rarity = rarity
              this@new.headFileName = head
            }
          } else {
            schema.name = name
            schema.limit = limit
            schema.rarity = rarity
            schema.headFileName = head
          }
          echo("成功")
        } else {
          echo("取消更新")
        }
      }
    }

  }

  private class DeleteStudent : CliktCommand(name = "d", help = "删除一个学生信息") {
    override fun run() {
      dbQuery {
        val schema = studentSelector(this)
        if (terminal.confirm("将删除学生信息: $schema")) {
          schema.delete()
        } else {
          echo("操作取消")
        }
      }
    }
  }

  private class QueryStudent : CliktCommand(name = "q", help = "查询学生信息") {
    override fun run() {
      dbQuery {
        val studentName = terminal.prompt("请输入学生名称", null) as String
        StudentSchema
          .find { StudentTable.name like "%$studentName%" }
          .toList()
          .map { "$it" }
          .forEach {
            echo(it)
          }
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
      dbQuery {
        var poolName: String? = null
        while (poolName.isNullOrBlank()) {
          poolName = terminal.prompt("请输入卡池名称", null) as String
        }
        val poolList = GachaPoolSchema
          .find { GachaPoolTable.name like "%$poolName%" }
          .toList()
        echo(poolList
          .mapIndexed { idx, it -> "$idx. ${it.name}(active=${it.active})" }
        )
        val idx =
          terminal.prompt("请输入卡池编号", default = 0, choices = List(poolList.size) { index -> index }) { input ->
            ConversionResult.Valid(input.toIntOrNull())
          } as Int
        echo(poolList[idx].toString())
      }
    }
  }

  private class CreatePool : CliktCommand(name = "c", help = "创建卡池") {
    override fun run() {
      dbQuery {
        val poolName = terminal.prompt("请输入卡池名称", null) as String
        if (GachaPoolSchema.find { GachaPoolTable.name eq poolName }.toList().firstOrNull() != null) {
          echo("卡池名称存在", err = true)
          return@dbQuery
        }
        if (terminal.confirm("创建卡池")) {
          GachaPoolSchema.new {
            name = poolName
            active = false
            students = listOf()
          }
        }
      }
    }
  }

  private class ActivePool : CliktCommand(name = "a", help = "激活卡池") {
    override fun run() {
      dbQuery {
        val pool = poolSelector(this)
        if (terminal.confirm("激活卡池")) {
          GachaPoolSchema.activePool(pool.id.value)
        }
      }
    }
  }

  private class DeletePool : CliktCommand(name = "d", help = "删除卡池") {
    override fun run() {
      dbQuery {
        val pool = poolSelector(this)
        echo(pool.toString())
        if (pool.active) {
          echo("删除当前卡池将自动激活上一个卡池")
        }
        if (terminal.confirm("删除卡池")) {
          pool.delete()
          val prv = GachaPoolSchema.all().orderBy(GachaPoolTable.id to SortOrder.DESC).limit(1).toList().first()
          GachaPoolSchema.activePool(prv.id.value)
          echo("已激活:\n$prv")
        }
      }
    }
  }

  private class UpdatePool : CliktCommand(name = "u", help = "更新卡池信息") {
    override fun run() {
      dbQuery {
        val poolName = terminal.prompt("请输入卡池名称", null)
        val pool = if (poolName.isNullOrBlank()) {
          GachaPoolSchema.all().orderBy(GachaPoolTable.id to SortOrder.DESC).limit(1).firstOrNull()
        } else {
          GachaPoolSchema.find { GachaPoolTable.name like "%$poolName%" }.firstOrNull()
        }
        if (pool == null) {
          echo("没找到对应的卡池", err = true)
          return@dbQuery
        }
        echo("卡池: $pool")
        val type = terminal.prompt(
          "请输入更改类型编号(0改名1改pickup)",
          default = 0,
          choices = listOf(0, 1)
        ) { input ->
          ConversionResult.Valid(input.toIntOrNull())
        } as Int
        when (type) {
          0 -> {
            val name = terminal.prompt("请输入卡池名", default = pool.name) as String
            if (terminal.confirm("新名: $name")) {
              pool.name = name
            }
          }

          1 -> {
            val editType = terminal.prompt("请输入更改类型编号(0增1删)", default = 0, choices = listOf(0, 1)) { input ->
              ConversionResult.Valid(input.toIntOrNull())
            } as Int
            when (editType) {
              0 -> {
                val student = studentSelector(this)
                if (terminal.confirm("添加学生: $student")) {
                  pool.students += listOf(student.id.value)
                }
              }

              1 -> {
                if (pool.students.isEmpty()) {
                  echo("空的学生列表", err = true)
                  return@dbQuery
                }
                val s = pool.toGachaPool().students
                echo(s.mapIndexed { idx, it -> "$idx. $it" })
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
                  pool.students = pool.students.toMutableList().also {
                    it.remove(select)
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
    dbQuery {
      var pool: GachaPoolSchema? = null
      while (pool == null) {
        val poolName = terminal.prompt("请输入卡池名称", null) as String
        val poolList = GachaPoolSchema
          .find { GachaPoolTable.name like "%$poolName%" }
          .toList()
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
}

fun studentSelector(command: CliktCommand): StudentSchema {
  return command.run {
    dbQuery {
      var student: StudentSchema? = null
      while (student == null) {
        val studentName = terminal.prompt("请输入学生名称", null) as String
        val studentList = StudentSchema
          .find { StudentTable.name like "%$studentName%" }
          .toList()
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
}
