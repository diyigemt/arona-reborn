package com.diyigemt.kivotos.inventory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 物品id规则
 *
 * UInt max: 2^32 = 4,294,967,296
 *
 * type id: 1,000,000
 *
 * subtype id: x,000,001
 *
 * 主类型 4,294种
 *
 * 子类型 999,999种
 */
private const val InventoryCategoryBase = 1000000u

val CurrencyList = listOf(
  InventoryCategory.COMMON_CURRENCY.toId(0u), // 信用點
  InventoryCategory.COMMON_CURRENCY.toId(1u), // 清輝石
  InventoryCategory.COMMON_CURRENCY.toId(2u), // AP
)

@Serializable(with = InventoryCategory.Companion::class)
enum class InventoryCategory(val id: UInt) {
  ERROR(0u),
  COMMON_CURRENCY(1u); //通用货币 0 信用点 1 清辉石 2 体力

  fun toId(subCategory: UInt) = id * InventoryCategoryBase + subCategory

  companion object : KSerializer<InventoryCategory> {
    private val map = entries.associateBy { it.id }

    /**
     * 根据全限定id
     */
    fun fromId(id: UInt) = checkNotNull(map[id / InventoryCategoryBase])
    fun toSubCategory(id: UInt) = id % InventoryCategoryBase
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("InventoryCategory", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder) = map[decoder.decodeInt().toUInt()] ?: ERROR

    override fun serialize(encoder: Encoder, value: InventoryCategory) = encoder.encodeInt(value.id.toInt())
  }
}

sealed interface Inventory {
  /**
   * 物品的全限定id, 全局唯一
   *
   * 规则与[InventoryCategoryBase]保持一致
   */
  val id: UInt

  /**
   * 物品的主类别
   */
  val category: InventoryCategory

  /**
   * 物品的子类别
   */
  val subCategory: UInt
}
