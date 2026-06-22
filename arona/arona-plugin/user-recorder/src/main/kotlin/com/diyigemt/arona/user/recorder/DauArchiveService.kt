package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.utils.currentDate
import io.github.crackthecodeabhi.kreds.connection.KredsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * 归档事务边界与历史读取。
 *
 * 进程内 [archiveMutex] 串行化整个 "扫描 → 读 Redis → 写 Mongo → 删 Redis" 流程, 防止启动归档与每日归档
 * (及未来可能的手动触发) 并发, 否则可能出现一方删 Redis、另一方读到空摘要覆盖 Mongo 的竞态。
 *
 * 所有 Redis 访问都走 [withArchiveRedis] 申请的**独立短生命周期连接**, 而非框架共享的实时连接:
 * 归档要 SCAN 全库 + 多轮往返, 一旦与实时消息路径共用同一条 kreds 连接的回复队列, 任一方在 read 中被
 * 取消留下孤儿回复, 就会让后续回复永久错位 (表现为 SCAN 读到非法的 *-1 空数组等)。独立连接各自隔离,
 * 用完即关, 取消/异常时连同孤儿回复一并销毁, 互不污染。
 */
internal object DauArchiveService {
  private val archiveMutex = Mutex()

  /**
   * 归档所有早于今天且仍在 Redis 的按天数据。
   * 单日失败相互隔离 (保留该日 Redis 数据, 下次启动/每日重试), 不阻断其它日期。
   */
  suspend fun archivePastDays() = archiveMutex.withLock {
    val today = LocalDate.parse(currentDate())
    val dates = withArchiveRedis { scanArchivableDates(today) }.sorted()
    if (dates.isEmpty()) {
      PluginMain.logger.info("DAU 归档检查完成: 无待归档数据")
      return@withLock
    }
    var archived = 0
    var skipped = 0
    var failed = 0
    for (date in dates) {
      try {
        // archiveOneDay 自管连接 (读、删各一条独立短连接), 单日失败相互隔离, 不复用可能已断连/重连到 db0 的连接。
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
    val redisSide = withArchiveRedis { readRedisDay(date) }

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

  /**
   * 申请一条独立 Redis 连接执行 [block], 无论成败 (含取消) 都在结束时关闭, 连同其上的孤儿回复一并销毁。
   *
   * 关闭包在 [NonCancellable] 内: 若 [block] 因协程取消而抛出, 普通 finally 里的挂起调用会被取消立即打断,
   * 导致 [KredsClient.close] 不执行而泄漏连接; NonCancellable 保证清理一定跑完。再叠加 [Dispatchers.IO]
   * 是因为 close 内部为阻塞式 runBlocking, 不应占用归档所在的默认调度线程。
   */
  private suspend fun <T> withArchiveRedis(block: suspend KredsClient.() -> T): T {
    val client = DatabaseProvider.newRedisConnection()
    return try {
      client.block()
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        runCatching { client.close() }
          .onFailure { PluginMain.logger.warn("关闭 DAU 归档 Redis 连接失败", it) }
      }
    }
  }

  /** SCAN 全库匹配 dau.*, 解析出早于 today 的可归档日期 (按日期去重, 容忍 rehash 重复返回)。 */
  private suspend fun KredsClient.scanArchivableDates(today: LocalDate): Set<LocalDate> {
    val dates = mutableSetOf<LocalDate>()
    val count = ArchiveConfig.scanCount.coerceAtLeast(1).toLong()
    var cursor = 0L
    do {
      val page = scan(cursor = cursor, matchPattern = "dau.*", count = count)
      page.elements.forEach { key -> DauArchiveKeys.parseArchivableDate(key, today)?.let(dates::add) }
      cursor = page.cursor
    } while (cursor != 0L)
    return dates
  }

  /**
   * 归档单日: 写 Mongo 成功后才删 Redis。返回 true=已归档, false=该日已无数据 (并发清理 / 早已归档)。
   *
   * 读与删各用一条独立短连接, 中间的 Mongo upsert 期间不持有任何 Redis 连接。这样连接不会在 Mongo 写期间
   * 空闲到触发 read-timeout/断线 —— 否则 kreds 下一条 del 会自动重连且**不重做 SELECT**, 可能落到 db0。
   * 拆开后, "可能在错误 db 上 del"的窗口被压到与框架内任意一条普通 kreds 命令同等量级 (相邻命令间的瞬时断线),
   * 不再跨越较慢的 Mongo 写。配合幂等 (upsert 成功才 del、del 前判 present、replaceOne 按 date), 残余窗口即便
   * 踩中也只是该日延后到下一轮, 不重复计数。
   */
  private suspend fun archiveOneDay(date: String): Boolean {
    val redisSide = withArchiveRedis { readRedisDay(date) }
    if (!redisSide.present) return false
    DauArchiveRepository.upsert(redisSide.summary)
    withArchiveRedis { del(*DauArchiveKeys.dailyKeys(date)) }
    return true
  }

  private suspend fun KredsClient.readRedisDay(date: String): RedisDailySummary {
    // 四条读命令走单次 pipeline: 同一个 execute 内整批发送/接收, 要么取自同一 db 的同一快照, 要么整体抛错。
    // 避免逐条命令之间被 kreds 透明重连 (重连不重做 SELECT) 拼出"部分目标库 + 部分 db0"的混合摘要,
    // 进而被误当作有效数据 upsert、随后又用全新连接把目标库真实数据删除而造成数据丢失。
    val raw = with(pipelined()) {
      val message = get(dayMessageKey(date))
      val userCount = hlen(dayDauKey(date))
      val contactCount = hlen(dayContactKey(date))
      val commandFlat = hgetAll(dayCommandKey(date))
      execute()
      RawRedisDay(
        message = message.get(),
        userCount = userCount.get(),
        contactCount = contactCount.get(),
        commandFlat = commandFlat.get(),
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
