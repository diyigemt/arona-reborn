package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import com.diyigemt.arona.database.dot
import com.diyigemt.arona.database.modifiedOne
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.kivotos.Kivotos
import com.mongodb.MongoException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import io.github.crackthecodeabhi.kreds.args.SetOption
import org.bson.conversions.Bson

/**
 * [InventoryService.grant] 的返回值.
 *
 * [DuplicateRequest.previousTraceId] 指向首次占位请求的 traceId, 方便把重复请求与已生效请求在日志里串起来;
 * Redis 键过期或客户端竞态下取不到时回落为 `"unknown"`.
 */
sealed class GrantResult {
  data object Ok : GrantResult()
  data class DuplicateRequest(val idempotencyKey: String, val previousTraceId: String) : GrantResult()
  data class Unsupported(val reason: String, val itemIds: List<UInt> = emptyList()) : GrantResult()
}

/** [InventoryService.consume] 的返回值; [Insufficient.shortages] 仅列出余量不足的缺口. */
sealed class ConsumeResult {
  data object Ok : ConsumeResult()
  data class Insufficient(val shortages: List<ItemDelta>) : ConsumeResult()
  data class DuplicateRequest(val idempotencyKey: String, val previousTraceId: String) : ConsumeResult()
  data class Unsupported(val reason: String, val itemIds: List<UInt> = emptyList()) : ConsumeResult()
}

/**
 * [InventoryService.execute] 的返回值: 把扣减与发放合并为一次原子事务.
 *
 * [Insufficient.shortages] 只会列出扣减段不满足的缺口, 发放段一定成功.
 */
sealed class ExecuteResult {
  data object Ok : ExecuteResult()
  data class Insufficient(val shortages: List<ItemDelta>) : ExecuteResult()
  data class DuplicateRequest(val idempotencyKey: String, val previousTraceId: String) : ExecuteResult()
  data class Unsupported(val reason: String, val itemIds: List<UInt> = emptyList()) : ExecuteResult()
}

/**
 * 库存操作的唯一门面.
 *
 * 设计原则:
 *  - 对外只暴露 [grant] / [consume] / [loadInventory]; 分桶路由在内部完成, 调用方不感知存储差异.
 *  - 单次调用产生的所有变更在同一文档内落一条 update, 借 Mongo 单文档原子性做到"要么全成要么全不成";
 *    跨文档场景(装备 + 材料)延后到扩展阶段用 saga 处理.
 *  - 幂等键占位放在所有可预检的失败路径 (路由/范围) 之后、写库之前; 写库异常会释放占位,
 *    避免把瞬时失败永久毒化. 审计写入失败不影响幂等也不回滚主流程 — 库存是主表, 审计是观测.
 *  - consume 用条件更新 (`$gte` + `$inc(-amount)`) 关掉 TOCTOU: 并发扣减不会把余额打负.
 */
object InventoryService {
  private const val IDEMPOTENCY_KEY_PREFIX = "kivotos.inv.idem"
  private const val IDEMPOTENCY_TTL_SECONDS: ULong = 600u

  suspend fun grant(uid: String, deltas: List<ItemDelta>, ctx: GrantContext): GrantResult {
    val normalized = normalize(deltas)
    if (normalized.isEmpty()) return GrantResult.Ok
    normalized.firstOrNull { it.amount <= 0L }?.let {
      return GrantResult.Unsupported("grant 仅接受正数增量; 扣减请改用 consume", listOf(it.itemId))
    }
    val routed = when (val r = route(normalized)) {
      is RouteResult.MissingTemplate -> return GrantResult.Unsupported("存在未配置模板的物品", r.itemIds)
      is RouteResult.EquipmentNotSupported -> return GrantResult.Unsupported("装备路径尚未实现", r.itemIds)
      is RouteResult.StackableOverflow ->
        return GrantResult.Unsupported("stackables 段仅支持 Int 范围, 单次增量越界", r.itemIds)
      is RouteResult.Ok -> r.items
    }

    occupyIdempotency(uid, ctx)?.let { previous ->
      return GrantResult.DuplicateRequest(ctx.idempotencyKey!!, previous)
    }

    try {
      UserInventoryDocument.findOrCreate(uid)
      writeUnconditionalIncrement(uid, routed, consume = false)
    } catch (e: Throwable) {
      releaseIdempotency(uid, ctx)
      throw e
    }

    recordAudit(uid, normalized, InventoryChangedEvent.Direction.GRANT, ctx)
    InventoryChangedEvent(uid, normalized, ctx.reason, ctx.traceId, InventoryChangedEvent.Direction.GRANT).broadcast()
    return GrantResult.Ok
  }

  suspend fun consume(uid: String, deltas: List<ItemDelta>, ctx: GrantContext): ConsumeResult {
    val normalized = normalize(deltas)
    if (normalized.isEmpty()) return ConsumeResult.Ok
    normalized.firstOrNull { it.amount <= 0L }?.let {
      return ConsumeResult.Unsupported("consume 仅接受正数扣减量", listOf(it.itemId))
    }
    val routed = when (val r = route(normalized)) {
      is RouteResult.MissingTemplate -> return ConsumeResult.Unsupported("存在未配置模板的物品", r.itemIds)
      is RouteResult.EquipmentNotSupported -> return ConsumeResult.Unsupported("装备路径尚未实现", r.itemIds)
      is RouteResult.StackableOverflow ->
        return ConsumeResult.Unsupported("stackables 段仅支持 Int 范围, 单次扣减越界", r.itemIds)
      is RouteResult.Ok -> r.items
    }

    occupyIdempotency(uid, ctx)?.let { previous ->
      return ConsumeResult.DuplicateRequest(ctx.idempotencyKey!!, previous)
    }

    val modified = try {
      UserInventoryDocument.findOrCreate(uid)
      writeConditionalDecrement(uid, routed)
    } catch (e: Throwable) {
      releaseIdempotency(uid, ctx)
      throw e
    }

    if (!modified) {
      // 条件不满足 = 余量不足, 读一份当前状态算清缺口供调用方展示
      releaseIdempotency(uid, ctx)
      val current = UserInventoryDocument.findOrCreate(uid)
      val shortages = routed.mapNotNull { r ->
        val have = current.amountOf(r.template, r.delta.itemId)
        if (have >= r.delta.amount) null else ItemDelta(r.delta.itemId, r.delta.amount - have)
      }
      return ConsumeResult.Insufficient(shortages)
    }

    recordAudit(uid, normalized, InventoryChangedEvent.Direction.CONSUME, ctx)
    InventoryChangedEvent(uid, normalized, ctx.reason, ctx.traceId, InventoryChangedEvent.Direction.CONSUME).broadcast()
    return ConsumeResult.Ok
  }

  /**
   * 原子地把扣减 + 发放压到一次 updateOne, 供"使用道具"等复合事务调用.
   *
   * 拒绝 `consumes` 与 `grants` 的 itemId 交集: Mongo 在一次 update 里对同一路径做两次 `$inc` 会
   * 报 path-conflict, 同时允许这种重叠也会让审计与实际变化脱节. 调用方应在 effect 层先合并.
   */
  suspend fun execute(
    uid: String,
    consumes: List<ItemDelta>,
    grants: List<ItemDelta>,
    ctx: GrantContext,
  ): ExecuteResult {
    if (consumes.isEmpty() && grants.isEmpty()) return ExecuteResult.Ok
    consumes.firstOrNull { it.amount <= 0L }?.let {
      return ExecuteResult.Unsupported("execute 的 consumes 仅接受正数扣减量", listOf(it.itemId))
    }
    grants.firstOrNull { it.amount <= 0L }?.let {
      return ExecuteResult.Unsupported("execute 的 grants 仅接受正数增量", listOf(it.itemId))
    }

    val normalizedConsumes = normalize(consumes)
    val normalizedGrants = normalize(grants)
    val overlap = normalizedConsumes.map { it.itemId }.toSet()
      .intersect(normalizedGrants.map { it.itemId }.toSet())
    if (overlap.isNotEmpty()) {
      return ExecuteResult.Unsupported("consumes 与 grants 不允许包含相同 itemId", overlap.toList())
    }

    val consumeRouted = when (val r = route(normalizedConsumes)) {
      is RouteResult.MissingTemplate -> return ExecuteResult.Unsupported("存在未配置模板的物品", r.itemIds)
      is RouteResult.EquipmentNotSupported -> return ExecuteResult.Unsupported("装备路径尚未实现", r.itemIds)
      is RouteResult.StackableOverflow ->
        return ExecuteResult.Unsupported("stackables 段仅支持 Int 范围, 单次扣减越界", r.itemIds)
      is RouteResult.Ok -> r.items
    }
    val grantRouted = when (val r = route(normalizedGrants)) {
      is RouteResult.MissingTemplate -> return ExecuteResult.Unsupported("存在未配置模板的物品", r.itemIds)
      is RouteResult.EquipmentNotSupported -> return ExecuteResult.Unsupported("装备路径尚未实现", r.itemIds)
      is RouteResult.StackableOverflow ->
        return ExecuteResult.Unsupported("stackables 段仅支持 Int 范围, 单次增量越界", r.itemIds)
      is RouteResult.Ok -> r.items
    }

    occupyIdempotency(uid, ctx)?.let { previous ->
      return ExecuteResult.DuplicateRequest(ctx.idempotencyKey!!, previous)
    }

    val modified = try {
      UserInventoryDocument.findOrCreate(uid)
      writeConditionalExecute(uid, consumeRouted, grantRouted)
    } catch (e: Throwable) {
      releaseIdempotency(uid, ctx)
      throw e
    }

    if (!modified) {
      releaseIdempotency(uid, ctx)
      val current = UserInventoryDocument.findOrCreate(uid)
      val shortages = consumeRouted.mapNotNull { r ->
        val have = current.amountOf(r.template, r.delta.itemId)
        if (have >= r.delta.amount) null else ItemDelta(r.delta.itemId, r.delta.amount - have)
      }
      return ExecuteResult.Insufficient(shortages)
    }

    if (normalizedConsumes.isNotEmpty()) {
      recordAudit(uid, normalizedConsumes, InventoryChangedEvent.Direction.CONSUME, ctx)
      InventoryChangedEvent(
        uid, normalizedConsumes, ctx.reason, ctx.traceId, InventoryChangedEvent.Direction.CONSUME,
      ).broadcast()
    }
    if (normalizedGrants.isNotEmpty()) {
      recordAudit(uid, normalizedGrants, InventoryChangedEvent.Direction.GRANT, ctx)
      InventoryChangedEvent(
        uid, normalizedGrants, ctx.reason, ctx.traceId, InventoryChangedEvent.Direction.GRANT,
      ).broadcast()
    }
    return ExecuteResult.Ok
  }

  /** 读取或创建用户仓库. 命名显式标注 "load" 而非 "snapshot" 以提醒调用方存在 ensure 副作用. */
  suspend fun loadInventory(uid: String): UserInventoryDocument = UserInventoryDocument.findOrCreate(uid)

  private suspend fun occupyIdempotency(uid: String, ctx: GrantContext): String? {
    val key = ctx.idempotencyKey ?: return null
    val fullKey = idempotencyRedisKey(uid, key)
    val setResult = redisDbQuery {
      set(fullKey, ctx.traceId, SetOption.Builder(nx = true, exSeconds = IDEMPOTENCY_TTL_SECONDS).build())
    }
    if (setResult == "OK") return null
    return redisDbQuery { get(fullKey) } ?: "unknown"
  }

  /**
   * 释放占位前先比对 value 中保存的 traceId, 只删除本次请求自己写入的占位.
   *
   * 裸 `DEL` 会在极端场景下误删他人占位 (A 占位 → TTL 过期 → B 以同 key 占位 → A 迟到 del 命中 B).
   * 这里用 GET + DEL 两步非原子实现: 足以挡掉"卡顿重试"场景, 代价是仍存在窄窗口 ABA —
   * 若需严格语义, 后续可换 Redis Lua `compare-and-del`.
   */
  private suspend fun releaseIdempotency(uid: String, ctx: GrantContext) {
    val key = ctx.idempotencyKey ?: return
    val fullKey = idempotencyRedisKey(uid, key)
    try {
      val currentToken = redisDbQuery { get(fullKey) } ?: return
      if (currentToken == ctx.traceId) {
        redisDbQuery { del(fullKey) }
      }
    } catch (e: Throwable) {
      Kivotos.logger.warn("release idempotency key failed", e)
    }
  }

  private fun idempotencyRedisKey(uid: String, key: String): String = "$IDEMPOTENCY_KEY_PREFIX.$uid.$key"

  /**
   * 同 itemId 合并、0 量过滤. 让上层奖励配置可以自由重复填写同一物品而不必先做归并.
   */
  private fun normalize(deltas: List<ItemDelta>): List<ItemDelta> =
    deltas.groupBy { it.itemId }
      .map { (itemId, group) -> ItemDelta(itemId, group.sumOf { it.amount }) }
      .filter { it.amount != 0L }

  /** 单次遍历同时完成模板查找、装备段拦截、stackable 单次增量 Int 范围校验. */
  private suspend fun route(deltas: List<ItemDelta>): RouteResult {
    val missing = mutableListOf<UInt>()
    val equipment = mutableListOf<UInt>()
    val overflow = mutableListOf<UInt>()
    val routed = mutableListOf<RoutedDelta>()

    for (delta in deltas) {
      val template = ItemTemplateCache.get(delta.itemId)
      if (template == null) {
        missing += delta.itemId
        continue
      }
      when (template.category.storage) {
        InventoryStorage.EQUIPMENT -> equipment += delta.itemId
        InventoryStorage.STACKABLE -> {
          if (delta.amount !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            overflow += delta.itemId
          } else {
            routed += RoutedDelta(template, delta)
          }
        }
        InventoryStorage.CURRENCY, InventoryStorage.RESOURCE -> routed += RoutedDelta(template, delta)
      }
    }

    return when {
      missing.isNotEmpty() -> RouteResult.MissingTemplate(missing)
      equipment.isNotEmpty() -> RouteResult.EquipmentNotSupported(equipment)
      overflow.isNotEmpty() -> RouteResult.StackableOverflow(overflow)
      else -> RouteResult.Ok(routed)
    }
  }

  private suspend fun writeUnconditionalIncrement(uid: String, items: List<RoutedDelta>, consume: Boolean) {
    val updates = incOnly(items, consume = consume) + Updates.set(UserInventoryDocument::updatedAt.name, currentDateTime())
    UserInventoryDocument.withCollection<UserInventoryDocument, Unit> {
      bulkWrite(listOf(UpdateOneModel(Filters.eq("_id", uid), Updates.combine(updates))))
    }
  }

  /**
   * 条件扣减: 把 "当前值 >= 扣减量" 作为 update filter, Mongo 单文档原子评估条件并应用 `$inc`.
   * modifiedCount == 1 表示全部扣减成功; 0 表示至少一项不足, 此时上层再读一次算缺口.
   */
  private suspend fun writeConditionalDecrement(uid: String, items: List<RoutedDelta>): Boolean {
    val filters = mutableListOf<Bson>(Filters.eq("_id", uid))
    filters += gteFiltersForConsume(items)
    val updates = incOnly(items, consume = true) + Updates.set(UserInventoryDocument::updatedAt.name, currentDateTime())
    val result: UpdateResult = UserInventoryDocument.withCollection<UserInventoryDocument, UpdateResult> {
      updateOne(Filters.and(filters), Updates.combine(updates))
    }
    return result.modifiedOne()
  }

  /**
   * execute 把扣减与发放压进同一次 updateOne, 让条件校验与余额变更共享同一个原子边界.
   * filter 只包含扣减段的 `$gte`; update 合并扣减的 `$inc(-)` 与发放的 `$inc(+)`, 末尾单次 `$set updatedAt`.
   */
  private suspend fun writeConditionalExecute(
    uid: String,
    consumeRouted: List<RoutedDelta>,
    grantRouted: List<RoutedDelta>,
  ): Boolean {
    val filters = mutableListOf<Bson>(Filters.eq("_id", uid))
    filters += gteFiltersForConsume(consumeRouted)
    val updates = incOnly(consumeRouted, consume = true) +
      incOnly(grantRouted, consume = false) +
      Updates.set(UserInventoryDocument::updatedAt.name, currentDateTime())
    val result: UpdateResult = UserInventoryDocument.withCollection<UserInventoryDocument, UpdateResult> {
      updateOne(Filters.and(filters), Updates.combine(updates))
    }
    return result.modifiedOne()
  }

  private fun gteFiltersForConsume(items: List<RoutedDelta>): List<Bson> = items.map { r ->
    val key = r.delta.itemId.asItemKey()
    when (r.template.category.storage) {
      InventoryStorage.CURRENCY ->
        Filters.gte(UserInventoryDocument::currencies.dot(key), r.delta.amount)
      InventoryStorage.RESOURCE ->
        Filters.gte(UserInventoryDocument::resources.dot(key, ResourceState::amount.name), r.delta.amount)
      InventoryStorage.STACKABLE ->
        Filters.gte(UserInventoryDocument::stackables.dot(key), r.delta.amount.toInt())
      InventoryStorage.EQUIPMENT -> error("equipment should have been filtered before write")
    }
  }

  private fun incOnly(items: List<RoutedDelta>, consume: Boolean): List<Bson> = items.map { r ->
    val key = r.delta.itemId.asItemKey()
    val signed = if (consume) -r.delta.amount else r.delta.amount
    when (r.template.category.storage) {
      InventoryStorage.CURRENCY ->
        Updates.inc(UserInventoryDocument::currencies.dot(key), signed)
      InventoryStorage.RESOURCE ->
        Updates.inc(UserInventoryDocument::resources.dot(key, ResourceState::amount.name), signed)
      InventoryStorage.STACKABLE ->
        Updates.inc(UserInventoryDocument::stackables.dot(key), signed.toInt())
      InventoryStorage.EQUIPMENT -> error("equipment should have been filtered before write")
    }
  }

  private suspend fun recordAudit(
    uid: String,
    deltas: List<ItemDelta>,
    direction: InventoryChangedEvent.Direction,
    ctx: GrantContext,
  ) {
    // 审计是观测, 不让 Mongo IO 抖动回滚主流程; 但不吞程序错误 (codec / 序列化 bug),
    // 否则会掩盖真实缺陷. 仅捕获 Mongo 基础设施异常.
    try {
      InventoryAuditLog.record(uid, deltas, direction, ctx)
    } catch (e: MongoException) {
      Kivotos.logger.warn("inventory audit log write failed", e)
    }
  }

  private data class RoutedDelta(val template: ItemTemplateDocument, val delta: ItemDelta)

  private sealed class RouteResult {
    data class Ok(val items: List<RoutedDelta>) : RouteResult()
    data class MissingTemplate(val itemIds: List<UInt>) : RouteResult()
    data class EquipmentNotSupported(val itemIds: List<UInt>) : RouteResult()
    data class StackableOverflow(val itemIds: List<UInt>) : RouteResult()
  }
}
