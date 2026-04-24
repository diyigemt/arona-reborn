package com.diyigemt.kivotos.inventory.use

import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.ItemDelta

/**
 * sideEffect 执行结果. 纯异常不足以承载"部分成功"语义 —— 礼包扣了、堆叠发完、装备发一半失败的混合场景
 * 需要结构化字段驱动 UseLog 的 partial-failed 记录.
 *
 * 用法:
 *  - sideEffect 全部成功: 返回 [SideEffectResult] 空参即可, 可选 [details] 承载成功现场 (如掷骰结果)
 *  - sideEffect 部分失败: 返回带 [partialFailure] 的实例, UseService 据此记 partial-failed 并告知用户
 *  - sideEffect 基础设施异常 (抛异常): 继续走原有异常路径, 与结构化失败互为补充
 */
data class SideEffectResult(
  val details: Map<String, String> = emptyMap(),
  val partialFailure: SideEffectPartialFailure? = null,
  val narrative: List<String> = emptyList(),
)

data class SideEffectPartialFailure(
  val reason: String,
  val details: Map<String, String> = emptyMap(),
)

/**
 * effect.apply 交给 [UseService] 的执行清单.
 *
 * [sideEffect] 在库存原子变更**成功之后**运行; 失败只记 [UseLog], 不回滚库存.
 * 这样设计是为了避免补偿链路自身也失败造成脏账 — 宁可把 partial-failed 显式化.
 *
 * **契约**: [sideEffect] **永不被 UseService 主动重放** — 同一次 use 如果需要重跑, 调用方应发起新一次
 * `/使用` 命令, 届时命令层会生成新的 traceId 与 idempotencyKey. 因此 sideEffect 内部调用非幂等子服务
 * (如 [com.diyigemt.kivotos.inventory.equipment.EquipmentService.grantFromTemplate]) 不会被 UseService 的
 * 自动重试复制一份. 但 sideEffect 内部若再调 `InventoryService.grant/execute`, 必须**使用派生 idempotencyKey**,
 * 否则会与外层 UseService → InventoryService.execute 的占位冲突, 稳定撞 DuplicateRequest.
 */
data class EffectApplyResult(
  val consumes: List<ItemDelta>,
  val grants: List<ItemDelta>,
  val sideEffect: (suspend () -> SideEffectResult)? = null,
  val narrative: List<String> = emptyList(),
)

/**
 * 道具"使用"行为的扩展点.
 *
 * preview 必须是纯计算 (不改任何状态); apply 可以返回 sideEffect 钩子.
 * 同一 [key] 对应唯一实现, 由 [ItemEffectRegistry] 在启动期收集.
 */
interface ItemEffect {
  val key: String

  /** 是否必须提供 [UseTarget] (None 以外). */
  val requiresTarget: Boolean

  /** 是否允许 count > 1 的批量使用. */
  val supportsBatch: Boolean

  /** 该 effect 能否处理传入的 target 类型. 命令层先调 preview 前校验, apply 会再查一次. */
  fun supports(target: UseTarget): Boolean

  suspend fun preview(req: UseRequest): UsePreview

  /**
   * **契约**: apply 必须是纯计算 — 不得直接修改库存、不得调用外部服务、不得落任何业务状态.
   * 所有副作用 (好感更新 / 远程请求 / 派发消息) 一律放到 [EffectApplyResult.sideEffect] 返回,
   * 由 [UseService] 在 [com.diyigemt.kivotos.inventory.InventoryService.execute] 成功之后执行.
   *
   * 违反此契约的 effect 会打穿库存原子边界: 当库存 execute 失败时, apply 内预先做的副作用
   * 无法被回滚, 产生不一致.
   */
  suspend fun apply(req: UseRequest, ctx: GrantContext): EffectApplyResult
}
