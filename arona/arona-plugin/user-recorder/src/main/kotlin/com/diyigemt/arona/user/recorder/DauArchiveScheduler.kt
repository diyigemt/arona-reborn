@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.utils.now
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * 归档调度: 启动后立即补一次往日归档, 之后每天在 [ArchiveConfig.archiveHour]:[ArchiveConfig.archiveMinute]
 * (系统默认时区) 触发。
 *
 * 每轮都重新计算下一次目标时刻 (而非固定 +24h), 规避 DST/漂移; [CancellationException] 透传以正确停机;
 * 其它异常仅记录, 不终止调度协程, 下一日继续。
 */
internal object DauArchiveScheduler {
  // 进程内单调递增的 runId 来源, 仅用于把每次调度的所有日志关联起来。
  private val runSeq = AtomicLong()

  fun launchIn(scope: CoroutineScope): Job = scope.launch {
    runOnce("startup")
    while (isActive) {
      delay(millisUntilNextRun())
      runOnce("daily")
    }
  }

  private suspend fun runOnce(trigger: String) {
    val run = ArchiveRun(runSeq.incrementAndGet(), trigger)
    PluginMain.logger.info("DAU 归档开始: runId={} trigger={}", run.id, run.trigger)
    try {
      DauArchiveService.archivePastDays(run)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      PluginMain.logger.error("DAU 归档任务异常, 将按每日调度重试: runId=${run.id} trigger=${run.trigger}", e)
    }
  }

  /** 计算距下一次 archiveHour:archiveMinute 的毫秒数 (今天该时刻已过则顺延到明天)。 */
  private fun millisUntilNextRun(): Long {
    val zone = TimeZone.currentSystemDefault()
    val current = now()
    val date = current.toLocalDateTime(zone).date
    val hour = ArchiveConfig.archiveHour.coerceIn(0, 23)
    val minute = ArchiveConfig.archiveMinute.coerceIn(0, 59)
    val todayTarget = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)
    val target = if (todayTarget > current) {
      todayTarget
    } else {
      LocalDateTime(date.plus(DatePeriod(days = 1)), LocalTime(hour, minute)).toInstant(zone)
    }
    return (target.toEpochMilliseconds() - current.toEpochMilliseconds()).coerceAtLeast(1_000L)
  }
}
