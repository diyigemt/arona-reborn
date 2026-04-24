package com.diyigemt.kivotos.inventory.use.effect

import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.GrantResult
import com.diyigemt.kivotos.inventory.InventoryCategory
import com.diyigemt.kivotos.inventory.InventoryService
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.diyigemt.kivotos.inventory.equipment.EquipmentService
import com.diyigemt.kivotos.inventory.equipment.GrantEquipmentResult
import com.diyigemt.kivotos.inventory.loot.DropResult
import com.diyigemt.kivotos.inventory.loot.LootRoll
import com.diyigemt.kivotos.inventory.loot.LootTableCache
import com.diyigemt.kivotos.inventory.loot.LootTableDocument
import com.diyigemt.kivotos.inventory.use.EffectApplyResult
import com.diyigemt.kivotos.inventory.use.ItemEffect
import com.diyigemt.kivotos.inventory.use.SideEffectPartialFailure
import com.diyigemt.kivotos.inventory.use.SideEffectResult
import com.diyigemt.kivotos.inventory.use.UseRequest
import com.diyigemt.kivotos.inventory.use.UsePreview
import com.diyigemt.kivotos.inventory.use.UseTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 礼包 effect.
 *
 * 设计取舍:
 *  - preview 不掷骰, 只告知"确认开启", 奖励在 apply 的 sideEffect 内 roll 并展示结果.
 *    这样不需要维护 preview/apply 之间的 roll 快照, 避免状态机爆炸.
 *  - sideEffect 按 category 把 roll 结果分派到:
 *      * stackable / currency / resource → InventoryService.grant (可合并 itemId)
 *      * EQUIPMENT → EquipmentService.grantFromTemplate (每件独立实例)
 *    这是一个已知的"非库存原子"路径, 部分失败通过 SideEffectPartialFailure 结构化留痕.
 *  - 单次开箱硬上限 [MAX_TOTAL_ROLLS] 防批量开箱把消息/审计/推送拉爆.
 */
object LootBoxEffect : ItemEffect {
  override val key: String = "loot_box"
  override val requiresTarget: Boolean = false
  override val supportsBatch: Boolean = true

  /** 单次 use 的 rolls * openCount + 保底条目 * openCount 总和上限. */
  const val MAX_TOTAL_ROLLS: Int = 200

  override fun supports(target: UseTarget): Boolean = target is UseTarget.None

  override suspend fun preview(req: UseRequest): UsePreview {
    val payload = decode(req.template.effectPayload)
    val table = LootTableCache.get(payload.lootTableId)
    val notes = mutableListOf<String>()
    if (table == null) {
      notes += "警告: 掉落表 '${payload.lootTableId}' 未配置, 无法开启"
    } else {
      val guaranteed = table.entries.filter { it.guaranteed }
      if (guaranteed.isNotEmpty()) {
        notes += "保底产出: " + guaranteed.joinToString(", ") { describeEntryRange(it.itemId, it.minCount, it.maxCount) }
      }
      val weighted = table.entries.filter { !it.guaranteed }
      if (weighted.isNotEmpty() && table.rolls > 0) {
        notes += "随机 ${table.rolls} 次从: " +
          weighted.joinToString(", ") { describeEntryRange(it.itemId, it.minCount, it.maxCount) }
      }
    }
    return UsePreview(
      consumes = listOf(ItemDelta(req.template.id, req.count.toLong())),
      grants = emptyList(), // roll 结果在 apply 时产生, preview 不承诺具体 deltas
      summary = "开启 ${req.template.name} x${req.count}",
      extraNotes = notes,
    )
  }

  override suspend fun apply(req: UseRequest, ctx: GrantContext): EffectApplyResult {
    val payload = decode(req.template.effectPayload)
    val consumes = listOf(ItemDelta(req.template.id, req.count.toLong()))
    val uid = req.uid
    val openCount = req.count

    val sideEffect: suspend () -> SideEffectResult = sideEffect@{
      val table = LootTableCache.get(payload.lootTableId)
      if (table == null) {
        return@sideEffect SideEffectResult(
          partialFailure = SideEffectPartialFailure(
            reason = "loot table '${payload.lootTableId}' missing",
            details = mapOf("lootTableId" to payload.lootTableId),
          ),
        )
      }
      if (exceedsRollLimit(table, openCount)) {
        return@sideEffect SideEffectResult(
          partialFailure = SideEffectPartialFailure(
            reason = "单次开箱总产出超过硬上限 $MAX_TOTAL_ROLLS",
            details = mapOf(
              "lootTableId" to table.id,
              "openCount" to openCount.toString(),
              "rolls" to table.rolls.toString(),
            ),
          ),
        )
      }

      val drops = LootRoll.roll(table, openCount)
      distribute(uid, drops, ctx, table)
    }

    return EffectApplyResult(
      consumes = consumes,
      grants = emptyList(),
      sideEffect = sideEffect,
      narrative = listOf("礼包产出请看下方结果"),
    )
  }

  /** 按模板 category 把 roll 结果分派到对应服务, 收集成功/失败到 details. */
  private suspend fun distribute(
    uid: String,
    drops: List<DropResult>,
    ctx: GrantContext,
    table: LootTableDocument,
  ): SideEffectResult {
    val stackableDeltas = mutableListOf<ItemDelta>()
    val equipmentDrops = mutableListOf<DropResult>()
    val unsupportedDrops = mutableListOf<DropResult>()

    for (drop in drops) {
      val tpl = ItemTemplateCache.get(drop.itemId)
      if (tpl == null) {
        unsupportedDrops += drop
        continue
      }
      when (tpl.category) {
        InventoryCategory.EQUIPMENT -> equipmentDrops += drop
        InventoryCategory.ERROR -> unsupportedDrops += drop
        else -> stackableDeltas += ItemDelta(drop.itemId, drop.count.toLong())
      }
    }

    val narrative = mutableListOf<String>()
    val details = mutableMapOf<String, String>()
    details["lootTableId"] = table.id
    details["rolledTotal"] = drops.size.toString()

    val failures = mutableListOf<String>()

    // 1. 堆叠类合并后一次 grant, 失败整体 partial
    // 关键: sideEffect 内部调 grant/execute 时, 幂等键必须与外层 use 的 idempotencyKey 分开.
    // 否则 UseService → InventoryService.execute 已占住外层 key, 这里再用同一 key 会稳定撞 DuplicateRequest.
    // 派生 key `<原 key>.loot.stackable` 让父键与子键独立, 同时仍由 traceId 串联可观测.
    if (stackableDeltas.isNotEmpty()) {
      val mergedStackable = stackableDeltas.groupBy { it.itemId }
        .map { (id, group) -> ItemDelta(id, group.sumOf { it.amount }) }
      details["stackablePlan"] = mergedStackable.joinToString(",") { "${it.itemId}:${it.amount}" }
      val stackCtx = ctx.copy(
        reason = ctx.reason + ".loot_box_stackable",
        idempotencyKey = ctx.idempotencyKey?.let { "$it.loot.stackable" },
      )
      when (val r = InventoryService.grant(uid, mergedStackable, stackCtx)) {
        is GrantResult.Ok -> {
          details["stackableGranted"] = "ok"
          narrative += "获得道具: " + describeDeltas(mergedStackable)
        }
        is GrantResult.DuplicateRequest -> {
          // sideEffect 不会被 UseService 主动重放, 撞到 duplicate 意味着历史遗留占位; 视为失败并要求人工处理
          failures += "堆叠类发放被幂等占位拦截 (前次 traceId=${r.previousTraceId})"
          details["stackableGranted"] = "duplicate:${r.previousTraceId}"
        }
        is GrantResult.Unsupported -> {
          failures += "堆叠类发放失败: ${r.reason}"
          details["stackableGranted"] = "unsupported:${r.reason}"
        }
      }
    }

    // 2. 装备每条独立 grant, 单条失败不阻断其他条目
    // 装备 ctx 不依赖幂等键去重; EquipmentService.grantFromTemplate 当前非幂等, sideEffect 永不重放是默认保证.
    val equippedIds = mutableListOf<String>()
    for (drop in equipmentDrops) {
      val equipCtx = ctx.copy(
        reason = ctx.reason + ".loot_box_equipment",
        idempotencyKey = null,
      )
      when (val r = EquipmentService.grantFromTemplate(uid, drop.itemId, drop.count, equipCtx)) {
        is GrantEquipmentResult.Ok -> equippedIds += r.instanceIds
        is GrantEquipmentResult.TemplateMissing -> failures += "装备模板 ${drop.itemId} 缺失"
        is GrantEquipmentResult.NotEquipmentCategory -> failures += "装备模板 ${drop.itemId} 类别不符"
        is GrantEquipmentResult.BadCount -> failures += "装备 ${drop.itemId} count=${drop.count} 非法: ${r.reason}"
      }
    }
    if (equippedIds.isNotEmpty()) {
      details["equipmentGranted"] = equippedIds.joinToString(",")
      narrative += "获得装备: ${equippedIds.size} 件"
    }

    // 3. 无效条目
    if (unsupportedDrops.isNotEmpty()) {
      details["unsupportedDrops"] = unsupportedDrops.joinToString(",") { "${it.itemId}:${it.count}" }
      failures += "存在无法识别的掉落条目: ${unsupportedDrops.size} 项"
    }

    return if (failures.isEmpty()) {
      SideEffectResult(details = details, narrative = narrative)
    } else {
      Kivotos.logger.warn("loot box partial failure uid=$uid trace=${ctx.traceId} failures=$failures details=$details")
      SideEffectResult(
        details = details,
        narrative = narrative,
        partialFailure = SideEffectPartialFailure(
          reason = failures.joinToString("; "),
          details = details,
        ),
      )
    }
  }

  private fun exceedsRollLimit(table: LootTableDocument, openCount: Int): Boolean {
    val guaranteedCount = table.entries.count { it.guaranteed }
    val total = (guaranteedCount + table.rolls).toLong() * openCount
    return total > MAX_TOTAL_ROLLS
  }

  private fun describeEntryRange(itemId: UInt, minC: Int, maxC: Int): String =
    if (minC == maxC) "$itemId x$minC" else "$itemId x$minC~$maxC"

  private fun describeDeltas(deltas: List<ItemDelta>): String =
    deltas.joinToString(", ") { "${it.itemId} x${it.amount}" }

  private fun decode(payload: JsonElement?): Payload {
    require(payload != null) { "LootBoxEffect 需要 effectPayload" }
    return json.decodeFromJsonElement(Payload.serializer(), payload)
  }

  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Payload(val lootTableId: String)
}
