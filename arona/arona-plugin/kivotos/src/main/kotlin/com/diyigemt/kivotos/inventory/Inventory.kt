package com.diyigemt.kivotos.inventory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 物品 id 编码规则: `mainCategory * InventoryCategoryBase + subCategory`.
 *
 * UInt 最大 2^32 ≈ 4.29e9, 按此基数拆分:
 *   - 主类: 最多 4294 档(本枚举的号段规划)
 *   - 子类: 最多 999999 档
 *
 * 主类号段按语义稳定度而非递增序号分配, 以便活动/扩展插入新大类时不必挤占历史号.
 */
private const val InventoryCategoryBase = 1000000u

/** 物品 id 与 Mongo map key 之间的小桥: BSON key 必须是 String, 领域层保持 UInt. */
fun UInt.asItemKey(): String = toString()
fun String.asItemId(): UInt = toUInt()

/**
 * 用户仓库首次创建时需要种子化的货币 id 集合.
 * AP 不在此列表: 作为受限资源走 [ResourceList].
 */
val CurrencyList = listOf(
  InventoryCategory.COMMON_CURRENCY.toId(0u), // 信用点
  InventoryCategory.COMMON_CURRENCY.toId(1u), // 清辉石
)

/** 用户仓库首次创建时需要种子化的资源 id 集合. 当前仅 AP. */
val ResourceList = listOf(
  InventoryCategory.RESOURCE.toId(0u), // AP
)

/**
 * 存储段划分: 同一用户仓库文档被拆成多段 map, 便于原子 `$inc` 与读路径差异化.
 * - [CURRENCY] / [RESOURCE] / [STACKABLE] 合并在单文档内, 靠 bulkWrite 单文档多 op 保证批量原子;
 * - [EQUIPMENT] 独立集合由 EquipmentService 处理, 本 MVP 先占位语义, 不走库存文档.
 */
enum class InventoryStorage {
  CURRENCY,
  RESOURCE,
  STACKABLE,
  EQUIPMENT,
}

@Serializable(with = InventoryCategory.Companion::class)
enum class InventoryCategory(
  val id: UInt,
  val storage: InventoryStorage,
) {
  ERROR(0u, InventoryStorage.STACKABLE),
  COMMON_CURRENCY(1u, InventoryStorage.CURRENCY),   // 子类: 0 信用点, 1 清辉石
  RESOURCE(2u, InventoryStorage.RESOURCE),          // 子类: 0 AP
  EVENT_CURRENCY(10u, InventoryStorage.CURRENCY),   // 活动/副本代币, 子类按活动递增
  MATERIAL(20u, InventoryStorage.STACKABLE),        // 升级/培养素材
  FRAGMENT(21u, InventoryStorage.STACKABLE),        // 学生/装备碎片
  CONSUMABLE(30u, InventoryStorage.STACKABLE),      // 消耗品 (扭蛋券/经验书/药剂)
  GIFT(40u, InventoryStorage.STACKABLE),            // 好感礼物
  LOOTBOX(50u, InventoryStorage.STACKABLE),         // 可开启礼包
  EQUIPMENT(100u, InventoryStorage.EQUIPMENT),      // 装备模板 id (实例见 Equipment 独立集合)
  QUEST_ITEM(200u, InventoryStorage.STACKABLE);

  fun toId(subCategory: UInt) = id * InventoryCategoryBase + subCategory

  companion object : KSerializer<InventoryCategory> {
    private val map = entries.associateBy { it.id }

    fun fromId(id: UInt) = checkNotNull(map[id / InventoryCategoryBase])
    fun toSubCategory(id: UInt) = id % InventoryCategoryBase

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("InventoryCategory", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder) = map[decoder.decodeInt().toUInt()] ?: ERROR
    override fun serialize(encoder: Encoder, value: InventoryCategory) = encoder.encodeInt(value.id.toInt())
  }
}

sealed interface Inventory {
  /** 全限定 id, 遵循 [InventoryCategoryBase] 编码规则. */
  val id: UInt

  /** 主类别, 决定存储段与路由. */
  val category: InventoryCategory

  /** 子类别 id (主类内偏移). */
  val subCategory: UInt
}
