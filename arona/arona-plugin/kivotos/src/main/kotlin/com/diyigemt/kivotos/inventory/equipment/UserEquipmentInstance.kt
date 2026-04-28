package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 装备单件实例. 与模板 [com.diyigemt.kivotos.inventory.ItemTemplateDocument] (EQUIPMENT 类) 通过 [tplId] 关联.
 *
 * 穿戴关系由 [equippedBy] 与 [slot] 共同表达:
 *   - 均为 null  → 在背包中未穿戴
 *   - 均非 null  → 正被 [equippedBy] 穿在 [slot] 槽位
 *   不允许"一个 null 一个非 null"的混合态; 这由 EquipmentService 的写路径保证
 *
 * 采用 uuid String 作为 _id, 避免为 ObjectId 引入自定义 codec.
 */
@Serializable
data class UserEquipmentInstance(
  @SerialName("_id")
  val id: String = uuid("equip"),
  val uid: String,
  val tplId: UInt,
  val enhance: Int = 0,
  val equippedBy: Int? = null,
  val slot: EquipmentSlot? = null,
  val createdAt: String = currentDateTime(),
  val updatedAt: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "UserEquipmentInstance"
    override val database get() = KivotosMongoDatabase.instance
  }
}
