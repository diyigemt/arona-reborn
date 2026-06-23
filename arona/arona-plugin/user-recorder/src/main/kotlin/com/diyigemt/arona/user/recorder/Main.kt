@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.BuildInSuperAdminCommandOwner
import com.diyigemt.arona.command.CommandManager
import com.diyigemt.arona.command.UnderDevelopment
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.*
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.plugins.AronaPlugin
import com.diyigemt.arona.plugins.AronaPluginDescription
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDate
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

// Redis key 构造、按天/累计键定义与聚合解析见 DauArchive.kt; MongoDB 归档见 DauArchive*.kt。

object PluginMain : AronaPlugin(
  AronaPluginDescription(
    id = BuildConfig.ID,
    name = BuildConfig.NAME,
    author = BuildConfig.AUTHOR,
    version = BuildConfig.VERSION,
    description = BuildConfig.DESCRIPTION
  )
) {
  override fun onLoad() {
    // MongoDB 归档: 仅在启用时创建连接并启动后台调度; 关闭时完全不接触 Mongo, 维持纯 Redis 记录路径。
    if (ArchiveConfig.enabled) {
      // 启动期打印一次生效配置, 便于排查归档异常时核对实际参数 (归档时刻等); 不打印任何密码。
      // scanCount 因 kreds 0.9.1 的 option 编码缺陷暂不下发 (用 plain SCAN), 故标注 scanCountApplied=false。
      val archiveAt = "%02d:%02d".format(ArchiveConfig.archiveHour.coerceIn(0, 23), ArchiveConfig.archiveMinute.coerceIn(0, 59))
      logger.info(
        "DAU MongoDB 归档已启用: scanMode=plain scanCountConfigured={} scanCountApplied=false archiveAt={} timezone={} mongoHost={} mongoPort={} mongoDb={} mongoCollection={}",
        ArchiveConfig.scanCount, archiveAt, TimeZone.currentSystemDefault(),
        ArchiveConfig.host, ArchiveConfig.port, ArchiveConfig.db, ArchiveConfig.collection,
      )
      coroutineContext[Job]?.invokeOnCompletion { DauArchiveRepository.close() }
      DauArchiveScheduler.launchIn(this)
    } else {
      logger.info("DAU MongoDB 归档未启用, 继续仅用 Redis 记录数据")
    }
    pluginEventChannel().subscribeAlways<TencentMessageEvent> { ev ->
      PluginMain.launch {
        // 统计消息数
        val today = currentDate()
        val currentDateTime = currentDateTime()
        val dauKey = dayDauKey(today)
        val contactKey = dayContactKey(today)
        val messageKey = dayMessageKey(today)
        val messageString =
          ev.message.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return@launch
        val commandStr =
          messageString.split(" ").toMutableList().removeFirstOrNull() ?: return@launch
        val command =
          CommandManager.matchCommandName(commandStr.replace("/", "")) ?: return@launch
        val dayCommandKey = dayCommandKey(today)
        redis {
          pipeline {
            incr(messageKey)
            hIncrBy(dayCommandKey, command, 1L)
            hIncrBy(CommandKey, command, 1L)
            hIncrBy(dauKey, ev.sender.id, 1L)
            hIncrBy(contactKey, ev.subject.id, 1L)
            hSet(UserKey, ev.sender.id to currentDateTime)
            hSet(ContactKey, ev.subject.id to currentDateTime)
          }
        }
      }
    }
    pluginEventChannel().subscribeAlways<TencentBotUserChangeEvent> { ev ->
      PluginMain.launch {
        val currentDateTime = currentDateTime()
        redis {
          pipeline {
            hSet(UserKey, ev.user.id to currentDateTime)
            hSet(ContactKey, ev.subject.id to currentDateTime)
          }
        }
      }
    }
  }
}

suspend fun getDauString(date: String): TencentCustomMarkdown {
  var md = tencentCustomMarkdown {
    h1(date)
  }
  // 累计指标永远只在 Redis: 全时段用户/环境数 + 各命令累计执行次数。
  val cumulative = redis {
    CumulativeDauSnapshot(
      userCount = hLen(UserKey),
      contactCount = hLen(ContactKey),
      command = decodeCountHash(hGetAll(CommandKey)),
    )
  }
  md += "contact: ${cumulative.contactCount}, user: ${cumulative.userCount}"

  // 当日指标: 当天读 Redis, 往日 Redis 缺失则回退归档库; Mongo 不可用时明确提示而非伪装成 0。
  var archiveFailed = false
  val daily = try {
    DauArchiveService.readForDisplay(date)
  } catch (e: CancellationException) {
    throw e
  } catch (e: ArchiveUnavailableException) {
    PluginMain.logger.warn("历史 DAU 归档读取失败: date=$date", e)
    md += "历史归档读取失败: ${e.message ?: "MongoDB 不可用"}"
    archiveFailed = true
    null
  }

  if (daily != null) {
    md += "user=${daily.userCount}, contact=${daily.contactCount}, message=${daily.message}"
  } else if (!archiveFailed && date < currentDate()) {
    // 仅在确认"读取成功但无该日数据"时提示未找到; 存储故障已在上面单独提示, 不再叠加误导为"无数据"。
    md += "未找到该日期的历史数据"
  }

  // 指令执行次数: 当日次数/累计次数。累计表 dau.command 从不删除, 必涵盖任何归档日出现过的命令。
  md append tencentCustomMarkdown {
    indexedList {
      cumulative.command.forEach { (name, total) ->
        +"$name: ${daily?.command?.get(name) ?: 0L}/$total"
      }
    }
  }
  return md
}

private data class CumulativeDauSnapshot(
  val userCount: Long,
  val contactCount: Long,
  val command: Map<String, Long>,
)

@Suppress("unused")
class DauClCommand : CommandLineSubCommand, com.diyigemt.arona.console.ConsoleSubCommand(name = "dau", helpText = "显示当日dau") {
  private val offset by argument().int().default(0)
  override fun run() {
    // dau
    val date = now().minus(DateTimePeriod(days = offset), TimeZone.currentSystemDefault()).toDate()
    runBlocking {
      getDauString(date)
    }
      .content
      .split("\n")
      .filter { it.isNotBlank() }
      .forEach {
        echo(it)
      }
  }
}

@Suppress("unused")
@UnderDevelopment
class DauCommand : AbstractCommand(
  BuildInSuperAdminCommandOwner,
  "dau",
  description = "展示当日dau",
) {
  private val offset by argument().int().default(0)

  suspend fun UserCommandSender.dau() {
    val date = now()
      .minus(DateTimePeriod(days = offset), TimeZone.currentSystemDefault())
      .toDate()
    val md = getDauString(date)
    sendMessage(md)
  }
}
