package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.communication.event.AbstractEvent

/**
 * 库存变更事件, 由 [InventoryService] 在落库成功后广播.
 *
 * - [deltas] 保留正数语义, 正负由 [direction] 表达, 订阅方无需对数量取绝对值
 * - 事件不经 kotlinx 序列化持久化, 故不加 `@Serializable`
 */
data class InventoryChangedEvent(
  val uid: String,
  val deltas: List<ItemDelta>,
  val reason: String,
  val traceId: String,
  val direction: Direction,
) : AbstractEvent() {
  enum class Direction {
    GRANT,
    CONSUME,
  }
}
