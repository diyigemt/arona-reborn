package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.arona.communication.event.AbstractEvent

/**
 * 装备生命周期事件, 由 [EquipmentService] 在对应操作原子成功后广播.
 *
 * 独立于 `InventoryChangedEvent` 的 GRANT/CONSUME 二元语义 — 装备变化天然超出"数量加减"的维度.
 * 不加 `@Serializable`, 事件不入库.
 */
sealed class EquipmentEvent : AbstractEvent() {
  abstract val uid: String
  abstract val instanceId: String
  abstract val tplId: UInt
  abstract val traceId: String
}

data class EquipmentCreatedEvent(
  override val uid: String,
  override val instanceId: String,
  override val tplId: UInt,
  override val traceId: String,
) : EquipmentEvent()

data class EquipmentEquippedEvent(
  override val uid: String,
  override val instanceId: String,
  override val tplId: UInt,
  val studentId: Int,
  val slot: EquipmentSlot,
  val replacedInstanceId: String?,
  override val traceId: String,
) : EquipmentEvent()

data class EquipmentUnequippedEvent(
  override val uid: String,
  override val instanceId: String,
  override val tplId: UInt,
  val studentId: Int,
  val slot: EquipmentSlot,
  override val traceId: String,
) : EquipmentEvent()

data class EquipmentEnhancedEvent(
  override val uid: String,
  override val instanceId: String,
  override val tplId: UInt,
  val fromLevel: Int,
  val toLevel: Int,
  override val traceId: String,
) : EquipmentEvent()
