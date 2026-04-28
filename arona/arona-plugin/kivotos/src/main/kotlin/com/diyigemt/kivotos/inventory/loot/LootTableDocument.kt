package com.diyigemt.kivotos.inventory.loot

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import java.util.concurrent.atomic.AtomicReference

/**
 * 掷骰条目.
 *
 * - [weight] 非保底条目在随机池里的权重, 必须 > 0; [guaranteed]=true 时忽略 [weight]
 * - [minCount] / [maxCount] 单次命中时产出的件数区间 (含两端); 两者相等即固定数量
 * - [itemId] 指向 ItemTemplateDocument; LootBoxEffect 按模板 category 分派到 Inventory / Equipment 发放
 */
@Serializable
data class DropEntry(
  val itemId: UInt,
  val weight: Int = 1,
  val minCount: Int = 1,
  val maxCount: Int = 1,
  val guaranteed: Boolean = false,
)

/**
 * 礼包/掉落表.
 *
 * - 保底 ([guaranteed]=true 的条目) 每次开启必出一次
 * - 非保底条目按 [weight] 权重掷 [rolls] 次, 每次独立命中一个条目
 * - 所有产出汇总后交给发奖链路 (stackable 合并同 itemId, EQUIPMENT 每件独立实例)
 *
 * 单次 use 的 `openCount` 会对 [rolls] 与保底条目各自乘倍; 上限由 LootBoxEffect 的 MAX_TOTAL_ROLLS 兜底.
 */
@Serializable
data class LootTableDocument(
  @BsonId
  @SerialName("_id")
  val id: String,
  val description: String = "",
  val rolls: Int = 1,
  val entries: List<DropEntry> = emptyList(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "LootTable"
    override val database get() = KivotosMongoDatabase.instance
  }
}

/**
 * LootTable 装载期缓存. 与 `ItemTemplateCache` 同策略, AtomicReference + Mutex 双检懒加载.
 *
 * 装载时做一次性完整性校验 (空池 / weight <= 0 / minCount > maxCount), 失败只 warn 保留其他表,
 * 避免一张坏表拖垮整个缓存.
 */
object LootTableCache {
  private val snapshotRef = AtomicReference<Map<String, LootTableDocument>?>(null)
  private val loadLock = Mutex()

  suspend fun get(id: String): LootTableDocument? = ensure()[id]

  suspend fun all(): Collection<LootTableDocument> = ensure().values

  suspend fun refresh(): Collection<LootTableDocument> = loadLock.withLock {
    val loaded = load()
    snapshotRef.set(loaded)
    loaded.values
  }

  private suspend fun ensure(): Map<String, LootTableDocument> {
    snapshotRef.get()?.let { return it }
    return loadLock.withLock {
      snapshotRef.get() ?: load().also(snapshotRef::set)
    }
  }

  private suspend fun load(): Map<String, LootTableDocument> {
    val all = LootTableDocument.withCollection<LootTableDocument, List<LootTableDocument>> {
      find().toList()
    }
    val valid = mutableMapOf<String, LootTableDocument>()
    for (tbl in all) {
      val err = validate(tbl)
      if (err != null) {
        Kivotos.logger.warn("loot table '${tbl.id}' invalid: $err, skipped")
        continue
      }
      valid[tbl.id] = tbl
    }
    return valid
  }

  private fun validate(tbl: LootTableDocument): String? {
    if (tbl.rolls < 0) return "rolls must be >= 0"
    if (tbl.entries.isEmpty()) return "entries empty"
    val weighted = tbl.entries.filter { !it.guaranteed }
    if (tbl.rolls > 0 && weighted.isEmpty()) return "rolls>0 but no weighted entries"
    for (e in tbl.entries) {
      if (!e.guaranteed && e.weight <= 0) return "entry itemId=${e.itemId} weight must be > 0"
      if (e.minCount <= 0 || e.maxCount < e.minCount) return "entry itemId=${e.itemId} bad count range"
    }
    return null
  }
}
