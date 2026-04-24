package com.diyigemt.kivotos.inventory.loot

import kotlin.random.Random

/**
 * 单次命中的产出 (itemId, count). 为什么与 [com.diyigemt.kivotos.inventory.ItemDelta] 拆开:
 * DropResult 是 roll 的原始结果, 可包含装备模板 id, 而 ItemDelta 语义上是"堆叠数值变化".
 * LootBoxEffect 在发奖阶段会把这份列表按模板 category 分派到 InventoryService / EquipmentService.
 */
data class DropResult(val itemId: UInt, val count: Int)

/**
 * 对给定 [LootTableDocument] 执行一次完整掷骰 (考虑 openCount 倍数).
 *
 * 实现:
 *  - 每个 guaranteed 条目固定发 openCount 次, 每次按 [DropEntry.minCount]..[DropEntry.maxCount] 取随机
 *  - 非保底 (weighted) 池做 rolls * openCount 次独立抽取, 每次按累积权重二分
 *  - 所有结果按 itemId 合并 count 前, 先保留"每次命中"粒度, 交给上层决定是否合并 (装备需要保留件数)
 */
object LootRoll {
  fun roll(
    table: LootTableDocument,
    openCount: Int,
    random: Random = Random.Default,
  ): List<DropResult> {
    require(openCount > 0) { "openCount must be > 0" }
    val out = mutableListOf<DropResult>()

    val guaranteed = table.entries.filter { it.guaranteed }
    val weighted = table.entries.filter { !it.guaranteed }
    val totalWeight = weighted.sumOf { it.weight }

    repeat(openCount) {
      for (entry in guaranteed) {
        val c = if (entry.minCount == entry.maxCount) entry.minCount
        else random.nextInt(entry.minCount, entry.maxCount + 1)
        if (c > 0) out += DropResult(entry.itemId, c)
      }
      if (totalWeight > 0) {
        repeat(table.rolls) {
          val pick = pickWeighted(weighted, totalWeight, random)
          val c = if (pick.minCount == pick.maxCount) pick.minCount
          else random.nextInt(pick.minCount, pick.maxCount + 1)
          if (c > 0) out += DropResult(pick.itemId, c)
        }
      }
    }
    return out
  }

  private fun pickWeighted(pool: List<DropEntry>, total: Int, random: Random): DropEntry {
    val r = random.nextInt(total)
    var cursor = 0
    for (entry in pool) {
      cursor += entry.weight
      if (r < cursor) return entry
    }
    // 理论上走不到: total = sum(weight), r < total 必在某段命中;
    // 兜底仅防御浮点/并发修改, 实际落地 pool 是不可变列表.
    return pool.last()
  }
}
