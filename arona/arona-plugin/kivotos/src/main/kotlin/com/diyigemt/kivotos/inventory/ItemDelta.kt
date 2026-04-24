package com.diyigemt.kivotos.inventory

import kotlinx.serialization.Serializable

/**
 * 单次库存变更的原子单位.
 *
 * [amount] 统一用正数表示数量, 正负语义由调用的服务方法决定
 * ([InventoryService.grant] / [InventoryService.consume]) — 这避免了"配置表里出现负数"
 * 这种容易被运营误填的坑.
 */
@Serializable
data class ItemDelta(
  val itemId: UInt,
  val amount: Long,
)
