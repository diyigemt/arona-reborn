package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.equipment.EquipmentPayload
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.bson.codecs.pojo.annotations.BsonId
import java.util.concurrent.atomic.AtomicReference

/**
 * 物品模板: 静态定义, 高频读、低频改.
 *
 * - [maxStack] 0 表示无上限; 装备类应恒为 1, 但 MVP 不强校验, 留给装备服务落地时再约束.
 * - [resourceCap] 仅对 [InventoryStorage.RESOURCE] 生效 (如 AP 体力上限),
 *   为 null 表示"由上层运营逻辑动态决定", 用户初始化时回落到 [ItemTemplateDocument.DEFAULT_RESOURCE_CAP].
 * - [effectKey] + [effectPayload] 不把副作用实现塞进 BSON; 注册表在代码侧绑定 handler.
 * - [tags] 做轻量筛选 (活动标签/商店分类), 不承担强语义.
 */
@Serializable
data class ItemTemplateDocument(
  @BsonId
  @SerialName("_id")
  override val id: UInt,
  override val category: InventoryCategory,
  override val subCategory: UInt,
  val name: String = "",
  val description: String = "",
  val quality: Int = 0,
  val maxStack: Int = 0,
  val tradable: Boolean = false,
  val sellable: Boolean = false,
  val resourceCap: Long? = null,
  val effectKey: String? = null,
  val effectPayload: JsonElement? = null,
  val tags: List<String> = listOf(),
) : Inventory {
  companion object : DocumentCompanionObject {
    override val documentName = "ItemTemplate"
    override val database get() = KivotosMongoDatabase.instance

    /** 模板缺失 resourceCap 时的兜底, 避免仓库创建时阻塞在模板未装载. */
    const val DEFAULT_RESOURCE_CAP = 999L
  }
}

/**
 * 模板内存缓存: 首次访问 suspend 装载全表, 之后走无锁快读.
 *
 * 首载与手动 [refresh] 之间用 [Mutex] 串行, 避免在启动抖动下打出多次全量查询.
 * 读路径用 [AtomicReference.get] 命中: 已装载时零同步成本.
 */
object ItemTemplateCache {
  private val snapshotRef = AtomicReference<Snapshot?>(null)
  private val loadLock = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun get(id: UInt): ItemTemplateDocument? = ensure().byId[id]

  /** 按精确名字查模板; 重名模板只会命中第一个, 另一重复会在装载时打 warn. */
  suspend fun getByName(name: String): ItemTemplateDocument? {
    val id = ensure().byName[name] ?: return null
    return get(id)
  }

  /**
   * 返回模板对应的装备载荷. 非 EQUIPMENT 模板 / payload 缺失 / 解码失败均返回 null.
   * 装载期一次性解析, 运行路径零 decode 开销.
   */
  suspend fun getEquipmentPayload(id: UInt): EquipmentPayload? = ensure().equipmentPayloads[id]

  suspend fun all(): Collection<ItemTemplateDocument> = ensure().byId.values

  suspend fun refresh(): Collection<ItemTemplateDocument> = loadLock.withLock {
    val loaded = load()
    snapshotRef.set(loaded)
    loaded.byId.values
  }

  private suspend fun ensure(): Snapshot {
    snapshotRef.get()?.let { return it }
    return loadLock.withLock {
      snapshotRef.get() ?: load().also(snapshotRef::set)
    }
  }

  private suspend fun load(): Snapshot {
    val all = ItemTemplateDocument.withCollection<ItemTemplateDocument, List<ItemTemplateDocument>> {
      find().toList()
    }
    val byId = all.associateBy { it.id }
    val byName = mutableMapOf<String, UInt>()
    val equipmentPayloads = mutableMapOf<UInt, EquipmentPayload>()
    for (tpl in all) {
      if (tpl.name.isNotBlank()) {
        val exist = byName[tpl.name]
        if (exist == null) {
          byName[tpl.name] = tpl.id
        } else {
          Kivotos.logger.warn(
            "duplicate item template name: '${tpl.name}' -> $exist vs ${tpl.id}, keep first",
          )
        }
      }
      if (tpl.category == InventoryCategory.EQUIPMENT && tpl.effectPayload != null) {
        runCatching { json.decodeFromJsonElement(EquipmentPayload.serializer(), tpl.effectPayload) }
          .onSuccess { equipmentPayloads[tpl.id] = it }
          .onFailure { Kivotos.logger.warn("decode EquipmentPayload failed for tpl ${tpl.id}: ${it.message}") }
      }
    }
    return Snapshot(byId, byName, equipmentPayloads)
  }

  private data class Snapshot(
    val byId: Map<UInt, ItemTemplateDocument>,
    val byName: Map<String, UInt>,
    val equipmentPayloads: Map<UInt, EquipmentPayload>,
  )
}
