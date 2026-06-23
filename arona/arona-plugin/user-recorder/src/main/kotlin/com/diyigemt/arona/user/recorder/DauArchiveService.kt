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
import java.util.concurrent.atomic.AtomicLong

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

  // 进程内单调递增的连接关联 id 来源。注意这是"客户端侧关联 id", 不是 Redis 服务端的 connection id,
  // 仅用于把同一条独立连接的 申请/就绪/操作/关闭 日志串起来。
  private val connectionSeq = AtomicLong()

  /**
   * 把 SCAN 游标同时按有符号 / 无符号 / 十六进制三种形式展开。Redis 游标是不透明的无符号 64 位整数, 而 kreds
   * 的 API 用 [Long] 承载: 当游标最高位被置位时在 Kotlin 侧表现为负数, 下一轮可能被编码成"负十进制游标"发给
   * 服务端。三种形式并列可直接判断失败是否源于游标符号问题 (坏参数) 还是别的原因。
   */
  private fun cursorFields(cursor: Long): String {
    val unsigned = cursor.toULong()
    return "cursorSigned=$cursor cursorUnsigned=$unsigned cursorHex=${unsigned.toString(16)} cursorNegative=${cursor < 0L}"
  }

  /**
   * 归档所有早于今天且仍在 Redis 的按天数据。
   * 单日失败相互隔离 (保留该日 Redis 数据, 下次启动/每日重试), 不阻断其它日期。
   */
  suspend fun archivePastDays(run: ArchiveRun) = archiveMutex.withLock {
    val today = LocalDate.parse(currentDate())
    val dates = withArchiveRedis("scan runId=${run.id} trigger=${run.trigger}") { connId ->
      scanArchivableDates(run, connId, today)
    }.sorted()
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
        // archiveOneDay 自管连接 (读、删各一条独立短连接), 单日失败相互隔离, 不复用可能已断连/重连到 db0 的连接。
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
    val redisSide = withArchiveRedis("display date=$date") { connId -> readRedisDay(connId, date) }

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
  private suspend fun <T> withArchiveRedis(op: String, block: suspend KredsClient.(connId: Long) -> T): T {
    // connId 在申请连接之前生成, 这样即便 newRedisConnection (建连 + SELECT) 自身失败, 也能在日志里关联到具体一次申请。
    val connId = connectionSeq.incrementAndGet()
    PluginMain.logger.debug("DAU 归档申请 Redis 连接: connId={} op={}", connId, op)
    val client = try {
      DatabaseProvider.newRedisConnection()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      PluginMain.logger.error("DAU 归档 Redis 连接申请失败: connId=$connId op=$op", e)
      throw e
    }
    PluginMain.logger.debug("DAU 归档 Redis 连接就绪: connId={} op={}", connId, op)
    return try {
      client.block(connId)
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        runCatching { client.close() }
          .onSuccess { PluginMain.logger.debug("DAU 归档 Redis 连接关闭: connId={} op={}", connId, op) }
          .onFailure { PluginMain.logger.warn("关闭 DAU 归档 Redis 连接失败: connId=$connId op=$op", it) }
      }
    }
  }

  /**
   * 用**无选项的 plain SCAN** 遍历整个键空间, 再由 [DauArchiveKeys.parseArchivableDate] 在客户端严格筛选出
   * `dau.{date}.{suffix}` 且早于 today 的日期 (按日期去重, 容忍 rehash 重复返回)。
   *
   * 这里刻意不向 [KredsClient.scan] 传 matchPattern/count: kreds 0.9.1 把 `MATCH`/`COUNT` 选项编码成了
   * `KeyValueArgument`, 而其 toString() 会把 "键 值" 拼成**单个** RESP 参数 (如 "MATCH dau.*"), Redis 收到这种
   * 粘连的 option token 直接回 `ERR syntax error` (已用裸 RESP socket 复现证实)。MATCH 仅缩小返回量、不减少服务端遍历,
   * 客户端正则过滤比 dau.* 更严格, 故去掉 MATCH 结果集完全一致; COUNT 只是批大小 hint, 去掉仅多几轮往返, 对每日
   * 一次的后台任务可接受。待迁移到能正确编码 SCAN 选项的客户端后即可恢复 MATCH/COUNT。
   */
  private suspend fun KredsClient.scanArchivableDates(run: ArchiveRun, connId: Long, today: LocalDate): Set<LocalDate> {
    val dates = mutableSetOf<LocalDate>()
    var cursor = 0L
    var round = 0
    var scannedKeyCount = 0L
    do {
      round++
      PluginMain.logger.debug(
        "DAU 归档 plain SCAN 请求: runId={} connId={} round={} {} clientFilter='dau.<date>.<suffix>'",
        run.id, connId, round, cursorFields(cursor),
      )
      val page = try {
        scan(cursor = cursor)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // MATCH/COUNT 已去除以规避 kreds 0.9.1 的 option 编码缺陷, 失败不再属于该已知问题。若 round=1、cursorSigned=0
        // 仍失败, 说明连最基础的 `SCAN <cursor>` 都被拒, 应转向排查服务端/代理、该连接 SELECT 后的回复队列或基础编码。
        PluginMain.logger.error(
          "DAU 归档 plain SCAN 失败: runId=${run.id} trigger=${run.trigger} connId=$connId round=$round " +
            "${cursorFields(cursor)} clientFilter='dau.<date>.<suffix>' intendedCommand=\"SCAN $cursor\" " +
            "completedRounds=${round - 1} scannedKeyCount=$scannedKeyCount candidateDateCount=${dates.size}",
          e,
        )
        throw e
      }
      scannedKeyCount += page.elements.size
      page.elements.forEach { key -> DauArchiveKeys.parseArchivableDate(key, today)?.let(dates::add) }
      // 同时打印本轮返回的 nextCursor (有符号 + 无符号): 它即为下一轮的输入游标, 据此可核对失败游标是否确为上轮返回值。
      PluginMain.logger.debug(
        "DAU 归档 plain SCAN 返回: runId={} connId={} round={} nextCursorSigned={} nextCursorUnsigned={} elements={} scannedKeyCount={} candidateDateCount={}",
        run.id, connId, round, page.cursor, page.cursor.toULong(), page.elements.size, scannedKeyCount, dates.size,
      )
      cursor = page.cursor
    } while (cursor != 0L)
    PluginMain.logger.debug(
      "DAU 归档 plain SCAN 完成: runId={} connId={} completedRounds={} scannedKeyCount={} candidateDateCount={}",
      run.id, connId, round, scannedKeyCount, dates.size,
    )
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
  private suspend fun archiveOneDay(run: ArchiveRun, date: String): Boolean {
    val redisSide = withArchiveRedis("read runId=${run.id} date=$date") { connId -> readRedisDay(connId, date) }
    if (!redisSide.present) {
      PluginMain.logger.debug("DAU 单日归档跳过: runId={} date={} reason=redis-not-present", run.id, date)
      return false
    }
    DauArchiveRepository.upsert(redisSide.summary)
    val dailyKeys = DauArchiveKeys.dailyKeys(date)
    withArchiveRedis("del runId=${run.id} date=$date") { connId ->
      // 记录 DEL 实际删除数量 (而非仅"已发起删除"): 与 expectedKeys 对比可暴露并发清理 / 落到错误 db 等异常。
      val deletedKeys = del(*dailyKeys)
      PluginMain.logger.debug(
        "DAU 单日归档删除: runId={} connId={} date={} expectedKeys={} deletedKeys={}",
        run.id, connId, date, dailyKeys.size, deletedKeys,
      )
    }
    return true
  }

  private suspend fun KredsClient.readRedisDay(connId: Long, date: String): RedisDailySummary {
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
    // raw 路径仍走 kreds 扁平 hgetAll (未过门面): 显式校验偶数长度, 与门面 hGetAll 的保护对齐——
    // 奇数意味着回复错位 (如连接回复队列被孤儿回复污染), 直接抛错暴露而非静默丢字段。
    require(raw.commandFlat.size % 2 == 0) { "redis hash response has odd element count: ${raw.commandFlat.size}" }
    val present =
      raw.message != null || raw.userCount > 0L || raw.contactCount > 0L || raw.commandFlat.isNotEmpty()
    val message = raw.message?.let {
      it.toLongOrNull() ?: error("non-numeric message count for $date: '$it'")
    } ?: 0L
    // 只输出聚合计数; 不打印 command 字段名 / 完整 hash, 避免噪音与无谓的明细外泄 (commandFieldCount = 交替列表长度的一半)。
    PluginMain.logger.debug(
      "DAU 归档读取: connId={} date={} present={} message={} userCount={} contactCount={} commandFieldCount={}",
      connId, date, present, message, raw.userCount, raw.contactCount, raw.commandFlat.size / 2,
    )
    return RedisDailySummary(
      present = present,
      summary = DauDailySummary(
        date = date,
        message = message,
        userCount = raw.userCount,
        contactCount = raw.contactCount,
        // PR1 过渡: 本路径仍走 raw kreds 的扁平 hgetAll, 先转成 Map 再喂给 (现已 Map 化的) decodeCountHash;
        // PR2 readRedisDay 改走门面 hGetAll 后, 此适配即可删除。
        command = decodeCountHash(raw.commandFlat.chunked(2).associate { (field, value) -> field to value }),
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
