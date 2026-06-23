package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.database.AronaRedis
import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.utils.currentDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/**
 * 归档事务边界与历史读取。
 *
 * 进程内 [archiveMutex] 串行化整个 "扫描 → 读 Redis → 写 Mongo → 删 Redis" 流程, 防止启动归档与每日归档
 * (及未来可能的手动触发) 并发, 否则可能出现一方删 Redis、另一方读到空摘要覆盖 Mongo 的竞态。这是**业务级
 * 事务边界**, 与底层 Redis 是否池化无关, 故保留。
 *
 * 所有 Redis 访问统一走框架门面 [DatabaseProvider.redisDbQuery] (底层 re.this 池化客户端)。归档不再自建独立
 * 连接: re.this 池化后各命令自行借/还连接, 协程取消只影响自身借用的那条连接, 不会污染其它连接的回复队列;
 * 且每条 (含重连的) 池连接都应用同一 db 配置, 不存在 kreds 时代 "重连不重做 SELECT 落到 db0" 的隐患。
 */
internal object DauArchiveService {
  private val archiveMutex = Mutex()

  /**
   * 归档所有早于今天且仍在 Redis 的按天数据。
   * 单日失败相互隔离 (保留该日 Redis 数据, 下次启动/每日重试), 不阻断其它日期。
   */
  suspend fun archivePastDays(run: ArchiveRun) = archiveMutex.withLock {
    val today = LocalDate.parse(currentDate())
    val dates = DatabaseProvider.redisDbQuery { scanArchivableDates(run, today) }.sorted()
    if (dates.isEmpty()) {
      PluginMain.logger.info("DAU 归档检查完成: runId={} trigger={} 无待归档数据", run.id, run.trigger)
      return@withLock
    }
    // 只记录候选规模与首尾日期: 启动期可能积压数百天, 打全量列表会形成超长单行且无助诊断。
    PluginMain.logger.debug(
      "DAU 归档候选: runId={} candidateCount={} firstDate={} lastDate={}",
      run.id, dates.size, dates.first(), dates.last(),
    )
    var archived = 0
    var skipped = 0
    var failed = 0
    for (date in dates) {
      try {
        // 单日失败相互隔离: 写 Mongo 成功后才删该日 Redis, 失败则保留该日数据等下一轮重试。
        if (archiveOneDay(run, date.toString())) archived++ else skipped++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        failed++
        PluginMain.logger.error("DAU 归档失败, 保留该日 Redis 数据: runId=${run.id} trigger=${run.trigger} date=$date", e)
      }
    }
    PluginMain.logger.info(
      "DAU 归档检查完成: runId={} trigger={} 候选={} 归档={} 跳过={} 失败={}",
      run.id, run.trigger, dates.size, archived, skipped, failed,
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
    val redisSide = DatabaseProvider.redisDbQuery { readRedisDay(date) }

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
   * 用服务端 `SCAN MATCH=dau.* COUNT=<scanCount>` 遍历键空间, 再由 [DauArchiveKeys.parseArchivableDate] 在客户端
   * 严格筛选出 `dau.{date}.{suffix}` 且早于 today 的日期 (按日期去重, 容忍 rehash 重复返回)。
   *
   * MATCH 仅缩小返回量、不替代客户端筛选 (客户端正则比 `dau.*` 更严格); COUNT 是批大小 hint, 只影响往返轮数
   * 而非结果集。re.this 正确编码 SCAN 选项 —— kreds 0.9.1 曾把 MATCH/COUNT 拼成单个 RESP token 触发
   * `ERR syntax error`, 迁移后该缺陷消失, 故恢复下发 MATCH/COUNT。
   */
  private suspend fun AronaRedis.scanArchivableDates(run: ArchiveRun, today: LocalDate): Set<LocalDate> {
    val dates = mutableSetOf<LocalDate>()
    // COUNT 至少为 1: 配置误填 0/负数时退化为合法的最小批提示, 而非把非法值下发给服务端。
    val count = ArchiveConfig.scanCount.coerceAtLeast(1).toLong()
    var cursor = "0"
    var round = 0
    var scannedKeyCount = 0L
    do {
      round++
      PluginMain.logger.debug(
        "DAU 归档 SCAN 请求: runId={} round={} cursor={} match='dau.*' count={}",
        run.id, round, cursor, count,
      )
      val page = try {
        scan(cursor = cursor, match = "dau.*", count = count)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        PluginMain.logger.error(
          "DAU 归档 SCAN 失败: runId=${run.id} trigger=${run.trigger} round=$round cursor=$cursor " +
            "match='dau.*' count=$count completedRounds=${round - 1} scannedKeyCount=$scannedKeyCount " +
            "candidateDateCount=${dates.size}",
          e,
        )
        throw e
      }
      scannedKeyCount += page.keys.size
      page.keys.forEach { key -> DauArchiveKeys.parseArchivableDate(key, today)?.let(dates::add) }
      PluginMain.logger.debug(
        "DAU 归档 SCAN 返回: runId={} round={} nextCursor={} keys={} scannedKeyCount={} candidateDateCount={}",
        run.id, round, page.cursor, page.keys.size, scannedKeyCount, dates.size,
      )
      cursor = page.cursor
    } while (cursor != "0")
    PluginMain.logger.debug(
      "DAU 归档 SCAN 完成: runId={} completedRounds={} scannedKeyCount={} candidateDateCount={}",
      run.id, round, scannedKeyCount, dates.size,
    )
    return dates
  }

  /**
   * 归档单日: 写 Mongo 成功后才删 Redis。返回 true=已归档, false=该日已无数据 (并发清理 / 早已归档)。
   *
   * 读与删是两个独立的门面调用, 中间的 Mongo upsert 期间不占用任何 Redis 连接 (池化下连接用完即还)。配合幂等
   * (upsert 成功才 del、del 前判 present、replaceOne 按 date), 即便单日中途失败也只是延后到下一轮重试, 不重复计数。
   */
  private suspend fun archiveOneDay(run: ArchiveRun, date: String): Boolean {
    val redisSide = DatabaseProvider.redisDbQuery { readRedisDay(date) }
    if (!redisSide.present) {
      PluginMain.logger.debug("DAU 单日归档跳过: runId={} date={} reason=redis-not-present", run.id, date)
      return false
    }
    DauArchiveRepository.upsert(redisSide.summary)
    val dailyKeys = DauArchiveKeys.dailyKeys(date)
    DatabaseProvider.redisDbQuery {
      // 记录 DEL 实际删除数量 (而非仅"已发起删除"): 与 expectedKeys 对比可暴露并发清理等异常。
      val deletedKeys = del(*dailyKeys)
      PluginMain.logger.debug(
        "DAU 单日归档删除: runId={} date={} expectedKeys={} deletedKeys={}",
        run.id, date, dailyKeys.size, deletedKeys,
      )
    }
    return true
  }

  /**
   * 顺序读取某日的四项 Redis 数据 (message / userCount / contactCount / command)。每条命令各借一次池连接,
   * 比按位置解析 pipeline 的 `List<RType>` 更清晰; 归档每日一次、性能无所谓。
   *
   * 注: 这四条读非原子。在**归档路径** (archivePastDays) 下只读早于今天的 key, 而实时写入只写今天的 key, 故归档读
   * 不会与实时写入交错。但本方法也被 [readForDisplay] 用于读取当天 (date == today) 摘要, 此时实时写入并发进行,
   * 可能读到"半新半旧"的当日摘要——这只是展示瞬时瑕疵 (详见 [readForDisplay] KDoc), 不影响已归档数据正确性。
   */
  private suspend fun AronaRedis.readRedisDay(date: String): RedisDailySummary {
    val rawMessage = get(dayMessageKey(date))
    val userCount = hLen(dayDauKey(date))
    val contactCount = hLen(dayContactKey(date))
    val command = hGetAll(dayCommandKey(date))

    val present = rawMessage != null || userCount > 0L || contactCount > 0L || command.isNotEmpty()
    val message = rawMessage?.let {
      it.toLongOrNull() ?: error("non-numeric message count for $date: '$it'")
    } ?: 0L
    // 只输出聚合计数; 不打印 command 字段名 / 完整 hash, 避免噪音与无谓的明细外泄。
    PluginMain.logger.debug(
      "DAU 归档读取: date={} present={} message={} userCount={} contactCount={} commandFieldCount={}",
      date, present, message, userCount, contactCount, command.size,
    )
    return RedisDailySummary(
      present = present,
      summary = DauDailySummary(
        date = date,
        message = message,
        userCount = userCount,
        contactCount = contactCount,
        command = decodeCountHash(command),
      ),
    )
  }
}
