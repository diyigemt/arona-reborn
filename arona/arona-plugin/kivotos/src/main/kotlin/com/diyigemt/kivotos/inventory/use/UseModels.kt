package com.diyigemt.kivotos.inventory.use

import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.ItemTemplateDocument
import kotlinx.serialization.Serializable

/**
 * 使用道具的目标实体. 用 sealed interface 把"无目标"、"学生"、"装备实例"显式建模,
 * 命令层解析后统一交给 effect, effect 通过 [ItemEffect.supports] 自行判断是否接受.
 */
sealed interface UseTarget {
  data object None : UseTarget
  data class Student(val id: Int) : UseTarget
  data class EquipmentInstance(val id: String) : UseTarget
}

/** effect 在 preview 阶段给出的本次使用全部库存变动与可读描述. */
data class UsePreview(
  val consumes: List<ItemDelta>,
  val grants: List<ItemDelta>,
  val summary: String,
  val extraNotes: List<String> = emptyList(),
)

data class UseRequest(
  val uid: String,
  val template: ItemTemplateDocument,
  val count: Int,
  val target: UseTarget,
)

/** [UseService.preview] 的结构化结果, 避免用异常作控制流. */
sealed class PreviewOutcome {
  data class Ok(val preview: UsePreview) : PreviewOutcome()
  data object TemplateMissing : PreviewOutcome()
  data object NoEffect : PreviewOutcome()
  data class BadTarget(val reason: String) : PreviewOutcome()
  data class BadCount(val reason: String) : PreviewOutcome()
  data class Insufficient(val shortages: List<ItemDelta>) : PreviewOutcome()
}

/**
 * [UseService.apply] 的结构化结果.
 *
 * [SideEffectPartialFailed] 表示库存已原子落库, 仅非库存副作用 (如好感更新) 失败.
 * 这类情况不回滚库存, 通过 [UseLog] 留痕供人工补偿.
 */
sealed class ApplyOutcome {
  data class Ok(val preview: UsePreview) : ApplyOutcome()
  data object TemplateMissing : ApplyOutcome()
  data object NoEffect : ApplyOutcome()
  data class BadTarget(val reason: String) : ApplyOutcome()
  data class BadCount(val reason: String) : ApplyOutcome()
  data class Insufficient(val shortages: List<ItemDelta>) : ApplyOutcome()
  data class InventoryFailed(val reason: String) : ApplyOutcome()
  data class DuplicateRequest(val previousTraceId: String) : ApplyOutcome()
  data class SideEffectPartialFailed(
    val preview: UsePreview,
    val useLogId: String,
    val reason: String,
  ) : ApplyOutcome()
}

@Serializable
enum class UseLogStatus {
  OK,
  INVENTORY_FAILED,
  SIDE_EFFECT_PARTIAL_FAILED,
}
