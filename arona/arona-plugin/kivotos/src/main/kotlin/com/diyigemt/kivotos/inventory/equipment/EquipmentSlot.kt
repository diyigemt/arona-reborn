package com.diyigemt.kivotos.inventory.equipment

import kotlinx.serialization.Serializable

/**
 * 装备槽位. MVP 只划分三档大类, 具体语义由模板的 `baseAttributes` 决定, 避免过早绑死到蓝档原作分类.
 * 同一学生同一槽位同时只能穿戴一件装备.
 */
@Serializable
enum class EquipmentSlot {
  WEAPON,
  ARMOR,
  ACCESSORY,
}
