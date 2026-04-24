package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.kivotos.inventory.ItemDelta
import kotlinx.serialization.Serializable

/**
 * EQUIPMENT 模板在 `ItemTemplateDocument.effectPayload` 里的强类型载荷.
 *
 * - [baseAttributes] 装备提供的加成, key 由业务语义决定 (attack / defense / hp 等)
 * - [maxEnhance] 强化等级上限 (含); 0 表示不可强化
 * - [enhanceCost] 按目标等级 (1..maxEnhance) 索引的材料消耗; 列表长度应 = [maxEnhance]
 *
 * 解码发生在 `ItemTemplateCache` 装载期, 运行路径零 decode 开销.
 */
@Serializable
data class EquipmentPayload(
  val slot: EquipmentSlot,
  val baseAttributes: Map<String, Int> = emptyMap(),
  val maxEnhance: Int = 0,
  val enhanceCost: List<List<ItemDelta>> = emptyList(),
)
