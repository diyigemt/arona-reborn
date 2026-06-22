package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.utils.currentDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery as redis

/**
 * 归档事务边界与历史读取。
 *
 * 进程内 [archiveMutex] 串行化整个 "扫描 → 读 Redis → 写 Mongo → 删 Redis" 流程, 防止启动归档与每日归档
 * (及未来可能的手动触发) 并发, 否则可能出现一方删 Redis、另一方读到空摘要覆盖 Mongo 的竞态。
 */
internal object DauArchiveService {
  private val archiveMutex = Mutex()

  /**
   * 归档所有早于今天且仍在 Redis 的按天数据。
   * 单日失败相互隔离 (保留该日 Redis 数据, 下次启动/每日重试), 不阻断其它日期。
   */
  suspend fun archivePastDays() = archiveMutex.withLock {
    val today = LocalDate.parse(currentDate())
    val dates = scanArchivableDates(today).sorted()
    if (dates.isEmpty()) {
      PluginMain.logger.info("DAU 归档检查完成: 无待归档数据")
      return@withLock
    }
    var archived = 0
    var skipped = 0
    var failed = 0
    for (date in dates) {
      try {
        if (archiveOneDay(date.toString())) archived++ else skipped++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        failed++
        PluginMain.logger.error("DAU 归档失败, 保留该日 Redis 数据: date=$date", e)
      }
    }
    PluginMain.logger.info(
      "DAU 归档检查完成: 候选=${dates.size}, 归档=$archived, 跳过=$skipped, 失败=$failed",
    )
  }

  /**
   * 读取用于展示的某天摘要:
   *  - 当天/未来: 始终读 Redis (空数据按零值返回);
   *  - 往日且 Redis 仍在 (未归档 / 删除失败): 读 Redis;
   *  - 归档关闭: 一律读 Redis (维持插件历史行为, 不回退、不报故障);
   *  - 归档开启、往日且 Redis 已无: 读 Mongo (返回 null 表示确无该日数据)。
   *
   * Mongo 读取异常统一包装为 [ArchiveUnavailableException], 供调用方区分 "无数据" 与 "存储故障"。
   *
   * 注: 此处刻意不持有 [archiveMutex] (否则启动期数百天补档会阻塞 dau 查看命令)。极端情况下若查询恰好与
   * 某日归档删除交错, 可能读到该日混合摘要 (如 message 非零、其余为零) —— 仅为该归档瞬间的展示瑕疵,
   * 不影响已写入 Mongo 的归档数据正确性, 下次查询即从 Mongo 取到完整值。
   */
  suspend fun readForDisplay(date: String): DauDailySummary? {
    val requested = LocalDate.parse(date)
    val today = LocalDate.parse(currentDate())
    val redisSide = readRedisDay(date)

    if (requested >= today) return redisSide.summary
    if (redisSide.present) return redisSide.summary
    if (!ArchiveConfig.enabled) return redisSide.summary

    return try {
      DauArchiveRepository.find(date)
    } catch (e: CancellationException) {
      throw e
    } catch (e: ArchiveUnavailableException) {
      throw e
    } catch (e: Throwable) {
      throw ArchiveUnavailableException("读取历史归档失败: date=$date", e)
    }
  }

  /** SCAN 全库匹配 dau.*, 解析出早于 today 的可归档日期 (按日期去重, 容忍 rehash 重复返回)。 */
  private suspend fun scanArchivableDates(today: LocalDate): Set<LocalDate> {
    val dates = mutableSetOf<LocalDate>()
    val count = ArchiveConfig.scanCount.coerceAtLeast(1).toLong()
    var cursor = 0L
    do {
      val page = redis { scan(cursor = cursor, matchPattern = "dau.*", count = count) }
      page.elements.forEach { key -> DauArchiveKeys.parseArchivableDate(key, today)?.let(dates::add) }
      cursor = page.cursor
    } while (cursor != 0L)
    return dates
  }

  /** 归档单日: 写 Mongo 成功后才删 Redis。返回 true=已归档, false=该日已无数据 (并发清理 / 早已归档)。 */
  private suspend fun archiveOneDay(date: String): Boolean {
    val redisSide = readRedisDay(date)
    if (!redisSide.present) return false
    DauArchiveRepository.upsert(redisSide.summary)
    redis { del(*DauArchiveKeys.dailyKeys(date)) }
    return true
  }

  private suspend fun readRedisDay(date: String): RedisDailySummary {
    val raw = redis {
      RawRedisDay(
        message = get(dayMessageKey(date)),
        userCount = hlen(dayDauKey(date)),
        contactCount = hlen(dayContactKey(date)),
        commandFlat = hgetAll(dayCommandKey(date)),
      )
    }
    val present =
      raw.message != null || raw.userCount > 0L || raw.contactCount > 0L || raw.commandFlat.isNotEmpty()
    val message = raw.message?.let {
      it.toLongOrNull() ?: error("non-numeric message count for $date: '$it'")
    } ?: 0L
    return RedisDailySummary(
      present = present,
      summary = DauDailySummary(
        date = date,
        message = message,
        userCount = raw.userCount,
        contactCount = raw.contactCount,
        command = decodeCountHash(raw.commandFlat),
      ),
    )
  }

  private data class RawRedisDay(
    val message: String?,
    val userCount: Long,
    val contactCount: Long,
    val commandFlat: List<String>,
  )
}
