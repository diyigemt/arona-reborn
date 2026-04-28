package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EquipmentEventKind {
  CREATED,
  EQUIPPED,
  UNEQUIPPED,
  ENHANCED,
}

/**
 * 装备变更审计. 独立成表的理由:
 *  - 装备维度的事件 (穿戴/强化) 不适合塞进 `InventoryAuditLog` 的 GRANT/CONSUME 二元方向
 *  - 独立 traceId 能把"消耗材料的 InventoryAuditLog 记录"与"装备升级的 EquipmentAuditLog 记录"串起来
 *
 * [before] / [after] 用 `Map<String, String>` 保留弱类型现场, 避免为每种事件单独定义 schema.
 */
@Serializable
data class EquipmentAuditLog(
  @SerialName("_id")
  val id: String = uuid("equip.log"),
  val uid: String,
  val instanceId: String,
  val tplId: UInt,
  val kind: EquipmentEventKind,
  val before: Map<String, String> = emptyMap(),
  val after: Map<String, String> = emptyMap(),
  val reason: String,
  val traceId: String,
  val ts: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "EquipmentAuditLog"
    override val database get() = KivotosMongoDatabase.instance

    suspend fun record(
      uid: String,
      instanceId: String,
      tplId: UInt,
      kind: EquipmentEventKind,
      reason: String,
      traceId: String,
      before: Map<String, String> = emptyMap(),
      after: Map<String, String> = emptyMap(),
    ) {
      withCollection<EquipmentAuditLog, Unit> {
        insertOne(
          EquipmentAuditLog(
            uid = uid,
            instanceId = instanceId,
            tplId = tplId,
            kind = kind,
            before = before,
            after = after,
            reason = reason,
            traceId = traceId,
          )
        )
      }
    }
  }
}
