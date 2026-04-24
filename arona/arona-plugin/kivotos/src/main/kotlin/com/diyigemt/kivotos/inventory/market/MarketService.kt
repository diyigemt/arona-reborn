package com.diyigemt.kivotos.inventory.market

import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.database.modifiedOne
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.ConsumeResult
import com.diyigemt.kivotos.inventory.ExecuteResult
import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.GrantResult
import com.diyigemt.kivotos.inventory.InventoryService
import com.diyigemt.kivotos.inventory.InventoryStorage
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.mongodb.ErrorCategory
import com.mongodb.MongoException
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

sealed class PostResult {
  data class Ok(val listing: MarketListingDocument) : PostResult()
  data class BadParam(val reason: String) : PostResult()
  data class InsufficientStock(val shortages: List<ItemDelta>) : PostResult()
  data object TooManyActive : PostResult()
  data class Untradable(val reason: String) : PostResult()
  data class WriteFailed(val reason: String) : PostResult()
}

sealed class BuyResult {
  data class Ok(val listing: MarketListingDocument) : BuyResult()
  data object NotFound : BuyResult()
  data object NotActive : BuyResult()
  data object Expired : BuyResult()
  data object SelfTrade : BuyResult()
  data class Insufficient(val shortages: List<ItemDelta>) : BuyResult()
  data class Unsupported(val reason: String) : BuyResult()

  /** 买家已扣款收货, 但 listing 没能推进到 SOLD. audit 已写 SETTLEMENT_STUCK, 需要人工对账. */
  data class SettlementStuck(val traceId: String) : BuyResult()
  data class WriteFailed(val reason: String) : BuyResult()
}

sealed class CancelResult {
  data object Ok : CancelResult()
  data object NotFound : CancelResult()
  data object NotOwner : CancelResult()
  data object NotActive : CancelResult()
  data class ReturnFailed(val reason: String) : CancelResult()
}

sealed class SettleResult {
  data class Ok(val amount: Long) : SettleResult()
  data object NotFound : SettleResult()
  data object NotOwner : SettleResult()
  data object NotSettlable : SettleResult()
  data class PayFailed(val reason: String) : SettleResult()
  data class WriteFailed(val reason: String) : SettleResult()
}

sealed class RetryReturnResult {
  data object Ok : RetryReturnResult()
  data object NotFound : RetryReturnResult()
  data object NotOwner : RetryReturnResult()
  data object NotReturnFailed : RetryReturnResult()
  data class ReturnStillFailed(val reason: String) : RetryReturnResult()
  data class WriteFailed(val reason: String) : RetryReturnResult()
}

data class ExpireBatchResult(val scanned: Int, val expired: Int, val returnFailed: Int)

/**
 * 挂牌市场服务层. 仅支持 STACKABLE 道具, 价格仅支持 CURRENCY 模板.
 *
 * **为什么不做 Mongo transaction**: 部署形态未知, 坚持"单文档 CAS + durable saga"范式.
 * 所有状态迁移都可重放, 失败留痕不丢资产.
 *
 * **幂等键派生约定** (均以 `InventoryService` 的 Redis 占位键为载体):
 *  - 挂牌扣货: `market.post.<postKey>` — postKey 由命令层生成, 网络抖动重试安全
 *  - 买家扣款+收货: `market.buy.<buyKey>` — buyKey 每次 ACTIVE→BUYING 时重新生成
 *  - 卖家收款: `market.settle.<settleKey>` — settleKey 每次 PENDING→PAYING 时重新生成
 *  - 返还商品: `market.return.<listingId>.<attempt>` — attempt 每次 RETURN_FAILED 重试 CAS 自增
 *
 * **为什么 DuplicateRequest 不当 Ok**: 上述派生键在正常路径不会撞 Redis 占位 (key 每次都是新生成或递增).
 * 一旦撞到就意味着有"遗留占位"或"状态机逻辑异常", 必须 warn + 返回可观察错误, 绝不默默吞掉.
 */
object MarketService {
  const val MAX_COUNT: Int = 10000
  const val MAX_ACTIVE_PER_UID: Int = 20
  const val MIN_PRICE_UNIT: Long = 1L
  val LOCK_DURATION: Duration = 5.minutes
  val DEFAULT_TTL: Duration = 48.hours

  fun defaultExpiresAt(): String = now().plus(DEFAULT_TTL).toDateTime()

  suspend fun post(
    sellerUid: String,
    itemId: UInt,
    count: Int,
    priceItemId: UInt,
    priceUnit: Long,
    expiresAt: String,
    postKey: String,
    ctx: GrantContext,
  ): PostResult {
    if (count <= 0 || count > MAX_COUNT) return PostResult.BadParam("数量必须在 1..$MAX_COUNT")
    if (priceUnit < MIN_PRICE_UNIT) return PostResult.BadParam("单价必须 >= $MIN_PRICE_UNIT")
    if (itemId == priceItemId) return PostResult.BadParam("价格货币不能与出售道具相同")
    val totalPrice = try {
      Math.multiplyExact(priceUnit, count.toLong())
    } catch (_: ArithmeticException) {
      return PostResult.BadParam("总价溢出")
    }

    val itemTemplate = ItemTemplateCache.get(itemId) ?: return PostResult.Untradable("道具模板不存在")
    if (itemTemplate.category.storage != InventoryStorage.STACKABLE) {
      return PostResult.Untradable("仅可交易可堆叠道具 (货币/资源/装备暂不支持)")
    }
    if ("untradable" in itemTemplate.tags) return PostResult.Untradable("该道具不可交易")

    val priceTemplate = ItemTemplateCache.get(priceItemId) ?: return PostResult.Untradable("货币模板不存在")
    if (priceTemplate.category.storage != InventoryStorage.CURRENCY) {
      return PostResult.Untradable("价格必须使用货币类道具")
    }

    val activeCount = MarketListingDocument.withCollection<MarketListingDocument, Long> {
      countDocuments(
        Filters.and(
          Filters.eq(MarketListingDocument::sellerUid.name, sellerUid),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
        )
      )
    }
    // 软上限: 纯读后写, 存在 TOCTOU, 极少量并发可能超出 20 → 后续若收紧, 改用 user 文档 CAS $inc. MVP 接受.
    if (activeCount >= MAX_ACTIVE_PER_UID) return PostResult.TooManyActive

    val consumeCtx = ctx.copy(idempotencyKey = "market.post.$postKey")
    when (val consume = InventoryService.consume(sellerUid, listOf(ItemDelta(itemId, count.toLong())), consumeCtx)) {
      is ConsumeResult.Ok -> Unit
      is ConsumeResult.Insufficient -> return PostResult.InsufficientStock(consume.shortages)
      is ConsumeResult.DuplicateRequest -> {
        // Redis 占位命中: 前次同 postKey 的 consume 已执行. 回读 listing 判断是否已入库.
        findByPostKey(postKey)?.let { return PostResult.Ok(it) }
        Kivotos.logger.error(
          "market post duplicate consume but listing missing uid=$sellerUid postKey=$postKey prev=${consume.previousTraceId}",
        )
        return PostResult.WriteFailed("挂牌状态不一致, 请联系管理员 (traceId=${ctx.traceId})")
      }
      is ConsumeResult.Unsupported -> return PostResult.Untradable(consume.reason)
    }

    val now = currentDateTime()
    val listing = MarketListingDocument(
      sellerUid = sellerUid,
      itemId = itemId,
      count = count,
      priceItemId = priceItemId,
      priceUnit = priceUnit,
      totalPrice = totalPrice,
      status = MarketStatus.ACTIVE,
      postKey = postKey,
      expiresAt = expiresAt,
      createdAt = now,
      updatedAt = now,
      traceId = ctx.traceId,
    )
    return try {
      MarketListingDocument.withCollection<MarketListingDocument, Unit> { insertOne(listing) }
      recordAudit(sellerUid, listing.id, MarketAuditKind.LISTED, ctx.reason, ctx.traceId, after = listing.auditMap())
      MarketListedEvent(sellerUid, listing.id, ctx.traceId).broadcast()
      PostResult.Ok(listing)
    } catch (e: MongoWriteException) {
      if (ErrorCategory.fromErrorCode(e.error.code) == ErrorCategory.DUPLICATE_KEY) {
        // 网络重试或并发重入落到同一 postKey unique 索引 → 回读既有 listing 返回 Ok, 幂等.
        findByPostKey(postKey)?.let { PostResult.Ok(it) }
          ?: PostResult.WriteFailed("postKey duplicate but listing not found")
      } else {
        handlePostInsertFailure(sellerUid, itemId, count, postKey, ctx, e)
      }
    } catch (e: MongoException) {
      handlePostInsertFailure(sellerUid, itemId, count, postKey, ctx, e)
    }
  }

  /**
   * consume 成功但 listing 写入失败 (非 DUPLICATE) 的兜底路径.
   *
   * 策略:
   *  1. best-effort 反向 grant, key = `market.post.<postKey>.rollback` (首用, 不撞原 consume 占位)
   *  2. 仅当 rollback 明确 GrantResult.Ok 才算退款成功; DuplicateRequest 可能是前次占位崩溃的遗留,
   *     grant 是否真的落库无法从 Redis 判断, 保守视为"未退款, 需人工确认"
   *  3. 任何"未确认退款"的分支都落一条 POST_ORPHAN 审计, 用 postKey 做 listingId 索引, 管理员可按
   *     postKey / traceId 检索 InventoryAuditLog 的原 consume 记录对账
   *
   * 这比"引入 POSTING 中间态 + 扫描 job" 的方案简单: 只在罕见故障时付出一次额外 grant + audit 开销,
   * 不给正常挂牌路径增加状态机复杂度.
   */
  private suspend fun handlePostInsertFailure(
    sellerUid: String,
    itemId: UInt,
    count: Int,
    postKey: String,
    ctx: GrantContext,
    cause: Throwable,
  ): PostResult {
    Kivotos.logger.error(
      "market post insert failed after consume uid=$sellerUid postKey=$postKey trace=${ctx.traceId}", cause,
    )
    val rollbackCtx = ctx.copy(idempotencyKey = "market.post.$postKey.rollback")
    val rollback = runCatching {
      InventoryService.grant(sellerUid, listOf(ItemDelta(itemId, count.toLong())), rollbackCtx)
    }
    val (refunded, note) = when {
      rollback.isFailure -> {
        Kivotos.logger.error(
          "market post rollback grant threw after insert failure uid=$sellerUid postKey=$postKey trace=${ctx.traceId}",
          rollback.exceptionOrNull(),
        )
        false to "rollback grant threw: ${rollback.exceptionOrNull()?.message}"
      }
      rollback.getOrNull() is GrantResult.Ok -> true to "rolled back"
      rollback.getOrNull() is GrantResult.DuplicateRequest -> {
        // rollback key 首用不该撞占位 — 撞到说明上次 rollback 占位后崩溃, grant 是否真落库无法判断.
        // 保守按"未退款"留 orphan 审计, 由人工按 postKey 对账 InventoryAuditLog 二选一修复.
        val prev = (rollback.getOrNull() as GrantResult.DuplicateRequest).previousTraceId
        Kivotos.logger.error(
          "market post rollback DuplicateRequest (unknown whether previous actually granted) uid=$sellerUid postKey=$postKey prev=$prev",
        )
        false to "rollback duplicate prev=$prev (refund status unknown, manual check required)"
      }
      else -> false to "rollback non-ok: ${rollback.getOrNull()}"
    }
    if (!refunded) {
      // 仅在"未确认退款"时留 orphan 审计; 正常退款不值得污染 audit 流.
      recordAudit(
        sellerUid, postKey, MarketAuditKind.POST_ORPHAN, ctx.reason, ctx.traceId,
        after = mapOf(
          "itemId" to itemId.toString(),
          "count" to count.toString(),
          "note" to note,
          "cause" to (cause.message ?: cause::class.simpleName.orEmpty()),
        ),
      )
    }
    return PostResult.WriteFailed("挂牌写入失败 ($note), traceId=${ctx.traceId}")
  }

  suspend fun buy(buyerUid: String, listingId: String, ctx: GrantContext): BuyResult {
    val listing = findById(listingId) ?: return BuyResult.NotFound
    if (listing.status != MarketStatus.ACTIVE) return BuyResult.NotActive
    if (listing.expiresAt <= currentDateTime()) return BuyResult.Expired
    if (listing.sellerUid == buyerUid) return BuyResult.SelfTrade

    val buyKey = uuid("market.buy")
    val now = currentDateTime()
    val lockExpiresAt = now().plus(LOCK_DURATION).toDateTime()
    // CAS filter 带 expiresAt > now: 防 findById 与 CAS 之间刚好跨过过期边界, 买家买到过期单.
    val locked = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
          Filters.gt(MarketListingDocument::expiresAt.name, now),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::status.name, MarketStatus.BUYING.name),
          Updates.set(MarketListingDocument::buyerUid.name, buyerUid),
          Updates.set(MarketListingDocument::buyKey.name, buyKey),
          Updates.set(MarketListingDocument::lockExpiresAt.name, lockExpiresAt),
          Updates.set(MarketListingDocument::settlementStatus.name, SettlementStatus.PENDING.name),
          Updates.set(MarketListingDocument::updatedAt.name, now),
        ),
      )
    }.modifiedOne()
    if (!locked) return BuyResult.NotActive

    val execCtx = ctx.copy(idempotencyKey = "market.buy.$buyKey")
    when (val exec = InventoryService.execute(
      buyerUid,
      consumes = listOf(ItemDelta(listing.priceItemId, listing.totalPrice)),
      grants = listOf(ItemDelta(listing.itemId, listing.count.toLong())),
      ctx = execCtx,
    )) {
      is ExecuteResult.Ok -> Unit
      is ExecuteResult.Insufficient -> {
        rollbackBuyingToActive(listingId, buyerUid, buyKey)
        return BuyResult.Insufficient(exec.shortages)
      }
      is ExecuteResult.DuplicateRequest -> {
        // buyKey 是新生成的 uuid, 不可能撞既有占位. 若真撞到说明 Redis 或调用路径异常 — 回滚 + error 报警.
        Kivotos.logger.error(
          "market buy execute got DuplicateRequest on fresh buyKey uid=$buyerUid listing=$listingId buyKey=$buyKey prev=${exec.previousTraceId}",
        )
        rollbackBuyingToActive(listingId, buyerUid, buyKey)
        return BuyResult.WriteFailed("买家扣款发生重复请求冲突, 请重试 (prev=${exec.previousTraceId})")
      }
      is ExecuteResult.Unsupported -> {
        rollbackBuyingToActive(listingId, buyerUid, buyKey)
        return BuyResult.Unsupported(exec.reason)
      }
    }

    // 买家 execute 已成功扣款收货. 后续 CAS 失败属于"钱货两讫但 listing 未推进"的灾难态, 须留痕以便对账.
    val sold = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.BUYING.name),
          Filters.eq(MarketListingDocument::buyKey.name, buyKey),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::status.name, MarketStatus.SOLD.name),
          Updates.set(MarketListingDocument::lockExpiresAt.name, null),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()
    if (!sold) {
      Kivotos.logger.error(
        "market buy CAS BUYING→SOLD failed but buyer already settled uid=$buyerUid listing=$listingId buyKey=$buyKey trace=${ctx.traceId}",
      )
      recordAudit(
        listing.sellerUid, listingId, MarketAuditKind.SETTLEMENT_STUCK, ctx.reason, ctx.traceId,
        before = listing.auditMap(),
        after = mapOf("buyerUid" to buyerUid, "buyKey" to buyKey),
      )
      return BuyResult.SettlementStuck(ctx.traceId)
    }

    val after = findById(listingId) ?: listing.copy(
      status = MarketStatus.SOLD,
      buyerUid = buyerUid,
      buyKey = buyKey,
      lockExpiresAt = null,
      settlementStatus = SettlementStatus.PENDING,
    )
    recordAudit(
      listing.sellerUid, listingId, MarketAuditKind.SOLD, ctx.reason, ctx.traceId,
      before = listing.auditMap(), after = after.auditMap(),
    )
    MarketSoldEvent(listing.sellerUid, listingId, buyerUid, ctx.traceId).broadcast()
    return BuyResult.Ok(after)
  }

  suspend fun cancel(sellerUid: String, listingId: String, ctx: GrantContext): CancelResult {
    val listing = findById(listingId) ?: return CancelResult.NotFound
    if (listing.sellerUid != sellerUid) return CancelResult.NotOwner
    if (listing.status != MarketStatus.ACTIVE) return CancelResult.NotActive

    val moved = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::status.name, MarketStatus.CANCELLING.name),
          Updates.set(MarketListingDocument::returnAttempt.name, 1),
          Updates.set(MarketListingDocument::returnTarget.name, MarketStatus.CANCELLED.name),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()
    if (!moved) return CancelResult.NotActive

    val outcome = finishReturn(
      sellerUid = sellerUid,
      listing = listing,
      fromStatus = MarketStatus.CANCELLING,
      target = MarketStatus.CANCELLED,
      attempt = 1,
      ctx = ctx,
    )
    return when (outcome) {
      is ReturnOutcome.Ok -> {
        MarketCancelledEvent(sellerUid, listingId, ctx.traceId).broadcast()
        CancelResult.Ok
      }
      is ReturnOutcome.Failed -> CancelResult.ReturnFailed(outcome.reason)
    }
  }

  suspend fun settle(sellerUid: String, listingId: String, ctx: GrantContext): SettleResult {
    val listing = findById(listingId) ?: return SettleResult.NotFound
    if (listing.sellerUid != sellerUid) return SettleResult.NotOwner
    if (listing.status != MarketStatus.SOLD || listing.settlementStatus != SettlementStatus.PENDING) {
      return SettleResult.NotSettlable
    }

    val settleKey = uuid("market.settle")
    val paying = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.SOLD.name),
          Filters.eq(MarketListingDocument::settlementStatus.name, SettlementStatus.PENDING.name),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::settlementStatus.name, SettlementStatus.PAYING.name),
          Updates.set(MarketListingDocument::settleKey.name, settleKey),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()
    if (!paying) return SettleResult.NotSettlable

    val payCtx = ctx.copy(idempotencyKey = "market.settle.$settleKey")
    when (val grant = InventoryService.grant(sellerUid, listOf(ItemDelta(listing.priceItemId, listing.totalPrice)), payCtx)) {
      is GrantResult.Ok -> Unit
      is GrantResult.DuplicateRequest -> {
        // 同 settleKey 在正常路径新生成, 撞占位是异常. 回滚到 PENDING, 下次 settle 新 key 再试.
        Kivotos.logger.error(
          "market settle grant duplicate on fresh settleKey uid=$sellerUid listing=$listingId key=$settleKey prev=${grant.previousTraceId}",
        )
        rollbackPayingToPending(listingId, settleKey)
        return SettleResult.PayFailed("结算占位冲突, 请稍后重试 (prev=${grant.previousTraceId})")
      }
      is GrantResult.Unsupported -> {
        rollbackPayingToPending(listingId, settleKey)
        recordAudit(
          sellerUid, listingId, MarketAuditKind.SETTLE_FAILED, ctx.reason, ctx.traceId,
          before = listing.auditMap(), after = mapOf("reason" to grant.reason),
        )
        return SettleResult.PayFailed(grant.reason)
      }
    }

    val paid = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::settlementStatus.name, SettlementStatus.PAYING.name),
          Filters.eq(MarketListingDocument::settleKey.name, settleKey),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::settlementStatus.name, SettlementStatus.PAID.name),
          Updates.set(MarketListingDocument::settledAt.name, currentDateTime()),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()
    if (!paid) {
      // grant 已发出但 CAS 未落. Redis 10min 占位保护幂等, 卖家重试 settle 时会拿到 NotSettlable 或
      // 再生成新 settleKey 进入 PAYING; 但由于此 settleKey 的 grant 已成功, 二次 settle 会 double-pay.
      // 这里是罕见灾难, audit 留痕待人工介入.
      Kivotos.logger.error(
        "market settle CAS PAYING→PAID failed after grant uid=$sellerUid listing=$listingId settleKey=$settleKey trace=${ctx.traceId}",
      )
      recordAudit(
        sellerUid, listingId, MarketAuditKind.SETTLE_FAILED, ctx.reason, ctx.traceId,
        before = listing.auditMap(), after = mapOf("settleKey" to settleKey, "note" to "CAS PAYING→PAID failed after grant"),
      )
      return SettleResult.WriteFailed("结算状态推进失败, 请联系管理员 (traceId=${ctx.traceId})")
    }

    recordAudit(
      sellerUid, listingId, MarketAuditKind.SETTLED, ctx.reason, ctx.traceId,
      before = listing.auditMap(),
      after = mapOf("settlementStatus" to SettlementStatus.PAID.name, "amount" to listing.totalPrice.toString()),
    )
    MarketSettledEvent(sellerUid, listingId, listing.totalPrice, ctx.traceId).broadcast()
    return SettleResult.Ok(listing.totalPrice)
  }

  suspend fun retryReturn(sellerUid: String, listingId: String, ctx: GrantContext): RetryReturnResult {
    val listing = findById(listingId) ?: return RetryReturnResult.NotFound
    if (listing.sellerUid != sellerUid) return RetryReturnResult.NotOwner
    if (listing.status != MarketStatus.RETURN_FAILED) return RetryReturnResult.NotReturnFailed
    val target = listing.returnTarget ?: run {
      Kivotos.logger.error("market retryReturn listing=$listingId missing returnTarget, defaulting to CANCELLED")
      MarketStatus.CANCELLED
    }
    val currentAttempt = listing.returnAttempt
    val nextAttempt = currentAttempt + 1

    // CAS inc returnAttempt 是并发重试的唯一闸门: 用户连点两下, 只有第一个 CAS 能通过, 第二个返回 WriteFailed.
    val moved = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listingId),
          Filters.eq(MarketListingDocument::status.name, MarketStatus.RETURN_FAILED.name),
          Filters.eq(MarketListingDocument::returnAttempt.name, currentAttempt),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::returnAttempt.name, nextAttempt),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()
    if (!moved) return RetryReturnResult.WriteFailed("retry CAS 失败, 可能已被并发处理")

    val outcome = finishReturn(
      sellerUid = sellerUid,
      listing = listing.copy(returnAttempt = nextAttempt),
      fromStatus = MarketStatus.RETURN_FAILED,
      target = target,
      attempt = nextAttempt,
      ctx = ctx,
    )
    return when (outcome) {
      is ReturnOutcome.Ok -> RetryReturnResult.Ok
      is ReturnOutcome.Failed -> RetryReturnResult.ReturnStillFailed(outcome.reason)
    }
  }

  /**
   * 扫描过期挂牌, 逐条推进到 EXPIRING → EXPIRED / RETURN_FAILED.
   * 并发两个调用同时扫, CAS `status=ACTIVE` 天然互斥, 只有一方能进 EXPIRING, 另一方跳过.
   */
  suspend fun expireListings(now: String = currentDateTime(), batch: Int = 50): ExpireBatchResult {
    val listings = MarketListingDocument.withCollection<MarketListingDocument, List<MarketListingDocument>> {
      find(
        Filters.and(
          Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
          Filters.lt(MarketListingDocument::expiresAt.name, now),
        )
      ).limit(batch).toList()
    }

    var expired = 0
    var returnFailed = 0
    for (listing in listings) {
      val traceId = uuid("market.expire")
      val ctx = GrantContext(
        reason = "market.expire.${listing.id}",
        sourceType = "system",
        sourceId = listing.id,
        traceId = traceId,
      )
      val moved = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
        updateOne(
          Filters.and(
            Filters.eq("_id", listing.id),
            Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
          ),
          Updates.combine(
            Updates.set(MarketListingDocument::status.name, MarketStatus.EXPIRING.name),
            Updates.set(MarketListingDocument::returnAttempt.name, 1),
            Updates.set(MarketListingDocument::returnTarget.name, MarketStatus.EXPIRED.name),
            Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
          ),
        )
      }.modifiedOne()
      if (!moved) continue // 竞争失败 / 状态已变

      when (finishReturn(listing.sellerUid, listing, MarketStatus.EXPIRING, MarketStatus.EXPIRED, 1, ctx)) {
        is ReturnOutcome.Ok -> {
          MarketExpiredEvent(listing.sellerUid, listing.id, traceId).broadcast()
          expired += 1
        }
        is ReturnOutcome.Failed -> returnFailed += 1
      }
    }
    return ExpireBatchResult(scanned = listings.size, expired = expired, returnFailed = returnFailed)
  }

  suspend fun search(itemId: UInt, limit: Int = 10): List<MarketListingDocument> =
    MarketListingDocument.withCollection<MarketListingDocument, List<MarketListingDocument>> {
      find(
        Filters.and(
          Filters.eq(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
          Filters.eq(MarketListingDocument::itemId.name, itemId),
          Filters.gt(MarketListingDocument::expiresAt.name, currentDateTime()),
        )
      ).sort(Sorts.ascending(MarketListingDocument::priceUnit.name)).limit(limit).toList()
    }

  suspend fun listByOwner(sellerUid: String, statuses: List<MarketStatus>): List<MarketListingDocument> =
    MarketListingDocument.withCollection<MarketListingDocument, List<MarketListingDocument>> {
      find(
        Filters.and(
          Filters.eq(MarketListingDocument::sellerUid.name, sellerUid),
          Filters.`in`(MarketListingDocument::status.name, statuses.map { it.name }),
        )
      ).sort(Sorts.descending(MarketListingDocument::updatedAt.name)).toList()
    }

  suspend fun findById(listingId: String): MarketListingDocument? =
    MarketListingDocument.withCollection<MarketListingDocument, MarketListingDocument?> {
      find(Filters.eq("_id", listingId)).limit(1).firstOrNull()
    }

  private suspend fun findByPostKey(postKey: String): MarketListingDocument? =
    MarketListingDocument.withCollection<MarketListingDocument, MarketListingDocument?> {
      find(Filters.eq(MarketListingDocument::postKey.name, postKey)).limit(1).firstOrNull()
    }

  private suspend fun rollbackBuyingToActive(listingId: String, buyerUid: String, buyKey: String) {
    runCatching {
      MarketListingDocument.withCollection<MarketListingDocument, Unit> {
        updateOne(
          Filters.and(
            Filters.eq("_id", listingId),
            Filters.eq(MarketListingDocument::status.name, MarketStatus.BUYING.name),
            Filters.eq(MarketListingDocument::buyerUid.name, buyerUid),
            Filters.eq(MarketListingDocument::buyKey.name, buyKey),
          ),
          Updates.combine(
            Updates.set(MarketListingDocument::status.name, MarketStatus.ACTIVE.name),
            Updates.set(MarketListingDocument::buyerUid.name, null),
            Updates.set(MarketListingDocument::buyKey.name, null),
            Updates.set(MarketListingDocument::lockExpiresAt.name, null),
            Updates.set(MarketListingDocument::settlementStatus.name, SettlementStatus.NOT_APPLICABLE.name),
            Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
          ),
        )
      }
    }.onFailure {
      Kivotos.logger.error("market rollbackBuyingToActive failed listing=$listingId buyKey=$buyKey", it)
    }
  }

  private suspend fun rollbackPayingToPending(listingId: String, settleKey: String) {
    runCatching {
      MarketListingDocument.withCollection<MarketListingDocument, Unit> {
        updateOne(
          Filters.and(
            Filters.eq("_id", listingId),
            Filters.eq(MarketListingDocument::settlementStatus.name, SettlementStatus.PAYING.name),
            Filters.eq(MarketListingDocument::settleKey.name, settleKey),
          ),
          Updates.combine(
            Updates.set(MarketListingDocument::settlementStatus.name, SettlementStatus.PENDING.name),
            Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
          ),
        )
      }
    }.onFailure {
      Kivotos.logger.error("market rollbackPayingToPending failed listing=$listingId settleKey=$settleKey", it)
    }
  }

  private sealed class ReturnOutcome {
    data object Ok : ReturnOutcome()
    data class Failed(val reason: String) : ReturnOutcome()
  }

  /**
   * 统一返还商品路径. 给 cancel / expire / retryReturn 共享:
   *  1. 用派生 idempotencyKey `market.return.<id>.<attempt>` 调 InventoryService.grant
   *  2. grant OK → CAS `fromStatus → target`, 成功就完工
   *  3. grant 失败或 CAS 失败 → CAS 到 RETURN_FAILED, 保留 returnAttempt 供后续重试
   */
  private suspend fun finishReturn(
    sellerUid: String,
    listing: MarketListingDocument,
    fromStatus: MarketStatus,
    target: MarketStatus,
    attempt: Int,
    ctx: GrantContext,
  ): ReturnOutcome {
    val grantCtx = ctx.copy(idempotencyKey = "market.return.${listing.id}.$attempt")
    val grantFailureReason = when (val grant = InventoryService.grant(
      sellerUid,
      listOf(ItemDelta(listing.itemId, listing.count.toLong())),
      grantCtx,
    )) {
      is GrantResult.Ok -> null
      is GrantResult.DuplicateRequest -> {
        // 正常路径不会撞占位 — 每次 retry 都 CAS 递增 attempt, 生成全新 key. 撞到说明前次
        // InventoryService.grant 走到 occupyIdempotency 后、writeUnconditionalIncrement 前崩溃,
        // Redis 占位留下但货未发. 盲目当 Ok 会把 listing 推进到 CANCELLED/EXPIRED 导致资产永久丢失.
        // 保守策略: 进 RETURN_FAILED, 让卖家等 Redis 占位 TTL (10min) 到期后再 /重发, 届时新 attempt
        // 的 key 与遗留占位不同, 安全绕过.
        "return grant idempotency conflict (prev=${grant.previousTraceId}, attempt=$attempt)"
      }
      is GrantResult.Unsupported -> grant.reason
    }

    if (grantFailureReason != null) {
      moveToReturnFailed(sellerUid, listing, target, grantFailureReason, ctx)
      return ReturnOutcome.Failed(grantFailureReason)
    }

    val done = MarketListingDocument.withCollection<MarketListingDocument, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", listing.id),
          Filters.eq(MarketListingDocument::status.name, fromStatus.name),
        ),
        Updates.combine(
          Updates.set(MarketListingDocument::status.name, target.name),
          Updates.set(MarketListingDocument::returnTarget.name, null),
          Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
        ),
      )
    }.modifiedOne()

    if (!done) {
      val reason = "CAS $fromStatus→$target failed after grant"
      moveToReturnFailed(sellerUid, listing, target, reason, ctx)
      return ReturnOutcome.Failed(reason)
    }

    val kind = if (target == MarketStatus.CANCELLED) MarketAuditKind.CANCELLED else MarketAuditKind.EXPIRED
    recordAudit(
      sellerUid, listing.id, kind, ctx.reason, ctx.traceId,
      before = listing.auditMap(),
      after = mapOf("status" to target.name, "returnAttempt" to attempt.toString()),
    )
    recordAudit(
      sellerUid, listing.id, MarketAuditKind.RETURNED, ctx.reason, ctx.traceId,
      after = mapOf("itemId" to listing.itemId.toString(), "count" to listing.count.toString()),
    )
    return ReturnOutcome.Ok
  }

  private suspend fun moveToReturnFailed(
    sellerUid: String,
    listing: MarketListingDocument,
    target: MarketStatus,
    reason: String,
    ctx: GrantContext,
  ) {
    runCatching {
      MarketListingDocument.withCollection<MarketListingDocument, Unit> {
        updateOne(
          Filters.eq("_id", listing.id),
          Updates.combine(
            Updates.set(MarketListingDocument::status.name, MarketStatus.RETURN_FAILED.name),
            Updates.set(MarketListingDocument::returnTarget.name, target.name),
            Updates.set(MarketListingDocument::updatedAt.name, currentDateTime()),
          ),
        )
      }
    }.onFailure {
      Kivotos.logger.error("market moveToReturnFailed write failed listing=${listing.id}", it)
    }
    recordAudit(
      sellerUid, listing.id, MarketAuditKind.RETURN_FAILED, ctx.reason, ctx.traceId,
      before = listing.auditMap(), after = mapOf("reason" to reason, "returnTarget" to target.name),
    )
    MarketReturnFailedEvent(sellerUid, listing.id, target, ctx.traceId).broadcast()
  }

  private suspend fun recordAudit(
    uid: String,
    listingId: String,
    kind: MarketAuditKind,
    reason: String,
    traceId: String,
    before: Map<String, String> = emptyMap(),
    after: Map<String, String> = emptyMap(),
  ) {
    // 审计失败不影响主流程. 与 InventoryAuditLog 保持一致: 只吞 Mongo 基础设施异常, 让 codec / 序列化 bug 暴露.
    try {
      MarketAuditLog.record(uid, listingId, kind, reason, traceId, before, after)
    } catch (e: MongoException) {
      Kivotos.logger.warn("market audit log write failed", e)
    }
  }

  private fun MarketListingDocument.auditMap(): Map<String, String> = mapOf(
    "status" to status.name,
    "sellerUid" to sellerUid,
    "buyerUid" to (buyerUid ?: ""),
    "itemId" to itemId.toString(),
    "count" to count.toString(),
    "priceItemId" to priceItemId.toString(),
    "totalPrice" to totalPrice.toString(),
    "settlementStatus" to settlementStatus.name,
    "returnAttempt" to returnAttempt.toString(),
  )
}
