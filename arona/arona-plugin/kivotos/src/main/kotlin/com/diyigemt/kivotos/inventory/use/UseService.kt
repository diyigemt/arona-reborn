package com.diyigemt.kivotos.inventory.use

import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.ExecuteResult
import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.InventoryService
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.diyigemt.kivotos.inventory.ItemTemplateDocument
import com.mongodb.MongoException

/**
 * 使用道具的业务编排.
 *
 * 流程:
 *   preview: validate → effect.preview → 读余量做预检 → 结构化返回
 *   apply:   validate (apply 不信任 preview) → effect.apply → InventoryService.execute
 *            → 成功后跑 sideEffect (失败仅记 UseLog, 不回滚)
 *
 * apply 的 validate 挡不掉的"preview → 确认"间隙里的余量变化, 由 InventoryService.execute 的
 * 条件更新兜底. UseService 只做"预检友好提示", 真正的原子边界仍在库存层.
 */
object UseService {
  suspend fun preview(
    uid: String,
    itemId: UInt,
    count: Int,
    target: UseTarget,
  ): PreviewOutcome {
    validate(itemId, count, target)?.let { return it.toPreview() }

    val (template, effect) = resolveEffect(itemId) ?: return PreviewOutcome.TemplateMissing
    val req = UseRequest(uid, template, count, target)
    val preview = effect.preview(req)

    val shortages = computeShortages(uid, preview)
    if (shortages.isNotEmpty()) return PreviewOutcome.Insufficient(shortages)
    return PreviewOutcome.Ok(preview)
  }

  suspend fun apply(
    uid: String,
    itemId: UInt,
    count: Int,
    target: UseTarget,
    ctx: GrantContext,
  ): ApplyOutcome {
    validate(itemId, count, target)?.let { return it.toApply() }

    val (template, effect) = resolveEffect(itemId) ?: return ApplyOutcome.TemplateMissing
    val req = UseRequest(uid, template, count, target)
    val result = effect.apply(req, ctx)
    val preview = UsePreview(
      consumes = result.consumes,
      grants = result.grants,
      summary = "使用 ${template.name} x$count",
      extraNotes = result.narrative,
    )

    when (val execResult = InventoryService.execute(uid, result.consumes, result.grants, ctx)) {
      is ExecuteResult.Ok -> Unit
      is ExecuteResult.Insufficient -> return ApplyOutcome.Insufficient(execResult.shortages)
      is ExecuteResult.DuplicateRequest -> return ApplyOutcome.DuplicateRequest(execResult.previousTraceId)
      is ExecuteResult.Unsupported -> return ApplyOutcome.InventoryFailed(execResult.reason)
    }

    val sideEffect = result.sideEffect
    var combinedPreview = preview
    if (sideEffect != null) {
      // 两种失败形态: 结构化 (effect 返回 partialFailure) 与 异常 (基础设施故障). 两条路径均导致 partial-failed
      val invocation = runCatching { sideEffect() }
      val err = invocation.exceptionOrNull()
      if (err != null) {
        Kivotos.logger.warn(
          "use side-effect threw: uid=$uid item=$itemId trace=${ctx.traceId}",
          err,
        )
        val logId = safeRecordUseLog(
          uid, itemId, count, target, effect.key, ctx.traceId,
          UseLogStatus.SIDE_EFFECT_PARTIAL_FAILED, "side_effect", err.message,
        ) ?: "unknown"
        return ApplyOutcome.SideEffectPartialFailed(preview, logId, err.message ?: "unknown")
      }
      val sideResult = invocation.getOrThrow()
      if (sideResult.narrative.isNotEmpty()) {
        combinedPreview = combinedPreview.copy(extraNotes = combinedPreview.extraNotes + sideResult.narrative)
      }
      val pf = sideResult.partialFailure
      if (pf != null) {
        Kivotos.logger.warn("use side-effect partial failure: uid=$uid item=$itemId trace=${ctx.traceId} reason=${pf.reason}")
        val logId = safeRecordUseLog(
          uid, itemId, count, target, effect.key, ctx.traceId,
          UseLogStatus.SIDE_EFFECT_PARTIAL_FAILED, "side_effect", pf.reason,
          details = pf.details,
        ) ?: "unknown"
        return ApplyOutcome.SideEffectPartialFailed(combinedPreview, logId, pf.reason)
      }
      safeRecordUseLog(
        uid, itemId, count, target, effect.key, ctx.traceId,
        UseLogStatus.OK, "apply", null, details = sideResult.details,
      )
      return ApplyOutcome.Ok(combinedPreview)
    }

    safeRecordUseLog(uid, itemId, count, target, effect.key, ctx.traceId, UseLogStatus.OK, "apply", null)
    return ApplyOutcome.Ok(combinedPreview)
  }

  /**
   * UseLog 是观测面, 不应让它在库存已原子成功后把 apply 推向失败.
   * 与 InventoryAuditLog 的处理一致: 只吞 Mongo 基础设施异常, 非 IO 异常 (codec / 序列化 bug) 继续抛出.
   */
  private suspend fun safeRecordUseLog(
    uid: String,
    itemId: UInt,
    count: Int,
    target: UseTarget,
    effectKey: String,
    traceId: String,
    status: UseLogStatus,
    phase: String,
    error: String?,
    details: Map<String, String> = emptyMap(),
  ): String? {
    return try {
      UseLog.record(uid, itemId, count, target, effectKey, traceId, status, phase, error, details)
    } catch (e: MongoException) {
      Kivotos.logger.warn("use log write failed", e)
      null
    }
  }

  private suspend fun validate(itemId: UInt, count: Int, target: UseTarget): GuardFail? {
    if (count <= 0) return GuardFail.BadCount("使用数量必须大于 0")
    val template = ItemTemplateCache.get(itemId) ?: return GuardFail.TemplateMissing
    val effect = template.effectKey?.let { ItemEffectRegistry.get(it) } ?: return GuardFail.NoEffect
    if (!effect.supportsBatch && count > 1) return GuardFail.BadCount("${template.name} 仅支持单次使用")
    if (effect.requiresTarget && target is UseTarget.None) return GuardFail.BadTarget("${template.name} 需要指定目标")
    if (!effect.supports(target)) return GuardFail.BadTarget("${template.name} 不接受当前目标类型")
    return null
  }

  private suspend fun resolveEffect(itemId: UInt): Pair<ItemTemplateDocument, ItemEffect>? {
    val template = ItemTemplateCache.get(itemId) ?: return null
    val effect = template.effectKey?.let { ItemEffectRegistry.get(it) } ?: return null
    return template to effect
  }

  private suspend fun computeShortages(uid: String, preview: UsePreview): List<ItemDelta> {
    if (preview.consumes.isEmpty()) return emptyList()
    // 合并 effect 未归并的同 itemId, 避免按拆开的每条独立比对余量导致缺口重复计算
    val merged = preview.consumes.groupBy { it.itemId }
      .map { (itemId, group) -> ItemDelta(itemId, group.sumOf { it.amount }) }
    val snapshot = InventoryService.loadInventory(uid)
    return merged.mapNotNull { delta ->
      val tpl = ItemTemplateCache.get(delta.itemId) ?: return@mapNotNull delta
      val have = snapshot.amountOf(tpl, delta.itemId)
      if (have >= delta.amount) null
      else ItemDelta(delta.itemId, delta.amount - have)
    }
  }

  private sealed class GuardFail {
    data object TemplateMissing : GuardFail()
    data object NoEffect : GuardFail()
    data class BadTarget(val reason: String) : GuardFail()
    data class BadCount(val reason: String) : GuardFail()

    fun toPreview(): PreviewOutcome = when (this) {
      TemplateMissing -> PreviewOutcome.TemplateMissing
      NoEffect -> PreviewOutcome.NoEffect
      is BadTarget -> PreviewOutcome.BadTarget(reason)
      is BadCount -> PreviewOutcome.BadCount(reason)
    }

    fun toApply(): ApplyOutcome = when (this) {
      TemplateMissing -> ApplyOutcome.TemplateMissing
      NoEffect -> ApplyOutcome.NoEffect
      is BadTarget -> ApplyOutcome.BadTarget(reason)
      is BadCount -> ApplyOutcome.BadCount(reason)
    }
  }
}
