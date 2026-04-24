package com.diyigemt.kivotos.inventory.equipment

import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.database.modifiedOne
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.ConsumeResult
import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.InventoryCategory
import com.diyigemt.kivotos.inventory.InventoryService
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.ItemTemplateCache
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.conversions.Bson

/** [EquipmentService.grantFromTemplate] 结果. */
sealed class GrantEquipmentResult {
  data class Ok(val instanceIds: List<String>) : GrantEquipmentResult()
  data object TemplateMissing : GrantEquipmentResult()
  data object NotEquipmentCategory : GrantEquipmentResult()
  data class BadCount(val reason: String) : GrantEquipmentResult()
}

/** [EquipmentService.equip] 结果. */
sealed class EquipResult {
  data class Ok(val replacedInstanceId: String?) : EquipResult()
  data object InstanceMissing : EquipResult()
  data object NotOwnedByUid : EquipResult()
  data object TemplateMissing : EquipResult()
  data object NotEquipmentCategory : EquipResult()
  data class AlreadyEquipped(val studentId: Int, val slot: EquipmentSlot) : EquipResult()
  data object WriteFailed : EquipResult()
}

sealed class UnequipResult {
  data object Ok : UnequipResult()
  data object InstanceMissing : UnequipResult()
  data object NotOwnedByUid : UnequipResult()
  data object NotEquipped : UnequipResult()
}

sealed class EnhanceResult {
  data class Ok(val fromLevel: Int, val toLevel: Int) : EnhanceResult()
  data object InstanceMissing : EnhanceResult()
  data object NotOwnedByUid : EnhanceResult()
  data object TemplateMissing : EnhanceResult()
  data object NotEquipmentCategory : EnhanceResult()
  data object AtMaxLevel : EnhanceResult()
  data class Insufficient(val shortages: List<ItemDelta>) : EnhanceResult()
  data class InventoryFailed(val reason: String) : EnhanceResult()
  data class DuplicateRequest(val previousTraceId: String) : EnhanceResult()
  data object WriteFailed : EnhanceResult()
}

/**
 * 装备实例生命周期服务.
 *
 * 关键设计:
 *  - 产出走独立 API 而不是塞进 `InventoryService.execute`: 装备无数量合并, grant 2 件同模板 = 2 件独立实例,
 *    `ItemDelta.amount` 在这里的语义会偏离"数量"本意.
 *  - 穿戴原子性采用"先 unset 旧 + 再条件 set 新"策略 (不依赖 Mongo transaction):
 *    第一步 unset 同学生同 slot 的旧装备 (幂等), 第二步对新装备用 CAS (equippedBy 必须为 null) 防抢占.
 *    两步都失败的最坏情况是旧装备已卸下但新装备未穿 — 对用户呈现"穿戴失败", 可直接重试,
 *    旧装备实体仍在, 无资产损失. 这比"套 unique index 让第二步报错"更易懂, 也比 transaction 更稳.
 *  - 强化复用 `InventoryService.consume` 扣材料, 成功后 `$inc enhance`; 不自造扣发路径.
 */
object EquipmentService {
  /**
   * 根据模板批量创建实例. `count > 1` 时产生多件独立装备.
   * 审计以每件独立写入, 方便按 instanceId 追账.
   */
  suspend fun grantFromTemplate(
    uid: String,
    tplId: UInt,
    count: Int,
    ctx: GrantContext,
  ): GrantEquipmentResult {
    if (count <= 0) return GrantEquipmentResult.BadCount("count 必须大于 0")
    val template = ItemTemplateCache.get(tplId) ?: return GrantEquipmentResult.TemplateMissing
    if (template.category != InventoryCategory.EQUIPMENT) return GrantEquipmentResult.NotEquipmentCategory

    val now = currentDateTime()
    val instances = (1..count).map {
      UserEquipmentInstance(
        id = uuid("equip"),
        uid = uid,
        tplId = tplId,
        createdAt = now,
        updatedAt = now,
      )
    }
    UserEquipmentInstance.withCollection<UserEquipmentInstance, Unit> {
      insertMany(instances)
    }
    for (inst in instances) {
      EquipmentAuditLog.record(
        uid = uid,
        instanceId = inst.id,
        tplId = tplId,
        kind = EquipmentEventKind.CREATED,
        reason = ctx.reason,
        traceId = ctx.traceId,
        after = mapOf("enhance" to "0"),
      )
      EquipmentCreatedEvent(uid, inst.id, tplId, ctx.traceId).broadcast()
    }
    return GrantEquipmentResult.Ok(instances.map { it.id })
  }

  suspend fun listByUid(uid: String): List<UserEquipmentInstance> =
    UserEquipmentInstance.withCollection<UserEquipmentInstance, List<UserEquipmentInstance>> {
      find(Filters.eq(UserEquipmentInstance::uid.name, uid)).toList()
    }

  suspend fun listByStudent(uid: String, studentId: Int): List<UserEquipmentInstance> =
    UserEquipmentInstance.withCollection<UserEquipmentInstance, List<UserEquipmentInstance>> {
      find(
        Filters.and(
          Filters.eq(UserEquipmentInstance::uid.name, uid),
          Filters.eq(UserEquipmentInstance::equippedBy.name, studentId),
        )
      ).toList()
    }

  suspend fun findById(instanceId: String): UserEquipmentInstance? =
    UserEquipmentInstance.withCollection<UserEquipmentInstance, UserEquipmentInstance?> {
      find(Filters.eq("_id", instanceId)).limit(1).firstOrNull()
    }

  suspend fun equip(uid: String, instanceId: String, studentId: Int, ctx: GrantContext): EquipResult {
    val instance = findById(instanceId) ?: return EquipResult.InstanceMissing
    if (instance.uid != uid) return EquipResult.NotOwnedByUid
    val template = ItemTemplateCache.get(instance.tplId) ?: return EquipResult.TemplateMissing
    if (template.category != InventoryCategory.EQUIPMENT) return EquipResult.NotEquipmentCategory
    val payload = ItemTemplateCache.getEquipmentPayload(instance.tplId) ?: return EquipResult.TemplateMissing
    val targetSlot = payload.slot

    if (instance.equippedBy == studentId && instance.slot == targetSlot) {
      return EquipResult.AlreadyEquipped(studentId, targetSlot)
    }

    val now = currentDateTime()

    // 第一步: 卸下同学生同 slot 的旧装备 (可能为空, 幂等)
    val replaced = UserEquipmentInstance.withCollection<UserEquipmentInstance, UserEquipmentInstance?> {
      find(
        Filters.and(
          Filters.eq(UserEquipmentInstance::uid.name, uid),
          Filters.eq(UserEquipmentInstance::equippedBy.name, studentId),
          Filters.eq(UserEquipmentInstance::slot.name, targetSlot),
        )
      ).limit(1).firstOrNull()
    }
    if (replaced != null && replaced.id != instanceId) {
      val unsetResult: UpdateResult = UserEquipmentInstance.withCollection<UserEquipmentInstance, UpdateResult> {
        updateOne(
          Filters.eq("_id", replaced.id),
          Updates.combine(
            Updates.set(UserEquipmentInstance::equippedBy.name, null),
            Updates.set(UserEquipmentInstance::slot.name, null),
            Updates.set(UserEquipmentInstance::updatedAt.name, now),
          ),
        )
      }
      if (!unsetResult.modifiedOne()) {
        // 并发下被别人卸掉: 继续即可, 第二步 CAS 会兜底
        Kivotos.logger.warn("equip: pre-unset race on uid=$uid replaced=${replaced.id}")
      } else {
        EquipmentAuditLog.record(
          uid = uid,
          instanceId = replaced.id,
          tplId = replaced.tplId,
          kind = EquipmentEventKind.UNEQUIPPED,
          reason = "equip.replace.${ctx.reason}",
          traceId = ctx.traceId,
          before = mapOf("equippedBy" to studentId.toString(), "slot" to targetSlot.name),
        )
        EquipmentUnequippedEvent(uid, replaced.id, replaced.tplId, studentId, targetSlot, ctx.traceId).broadcast()
      }
    }

    // 第二步: CAS 穿上新装备 (equippedBy 必须为 null, 防抢占).
    // 并发场景下两个请求可能都通过 CAS, 最终靠 partial unique index (uid, equippedBy, slot) 挡住二者其一
    // 抛 DUPLICATE_KEY, 在此处 catch 为 WriteFailed.
    val setFilter = Filters.and(
      Filters.eq("_id", instanceId),
      Filters.eq(UserEquipmentInstance::uid.name, uid),
      Filters.eq(UserEquipmentInstance::equippedBy.name, null),
    )
    val setUpdate = Updates.combine(
      Updates.set(UserEquipmentInstance::equippedBy.name, studentId),
      Updates.set(UserEquipmentInstance::slot.name, targetSlot),
      Updates.set(UserEquipmentInstance::updatedAt.name, now),
    )
    val setResult = try {
      UserEquipmentInstance.withCollection<UserEquipmentInstance, UpdateResult> {
        updateOne(setFilter, setUpdate)
      }
    } catch (e: MongoWriteException) {
      if (ErrorCategory.fromErrorCode(e.error.code) == ErrorCategory.DUPLICATE_KEY) {
        Kivotos.logger.warn("equip: unique-slot constraint rejected uid=$uid inst=$instanceId (并发已抢占, 可重试)")
        return EquipResult.WriteFailed
      }
      throw e
    }
    if (!setResult.modifiedOne()) {
      Kivotos.logger.warn("equip: set-new CAS failed uid=$uid inst=$instanceId (旧装备已卸下但未穿新, 可重试)")
      return EquipResult.WriteFailed
    }

    EquipmentAuditLog.record(
      uid = uid,
      instanceId = instanceId,
      tplId = instance.tplId,
      kind = EquipmentEventKind.EQUIPPED,
      reason = ctx.reason,
      traceId = ctx.traceId,
      after = mapOf("equippedBy" to studentId.toString(), "slot" to targetSlot.name),
    )
    EquipmentEquippedEvent(
      uid, instanceId, instance.tplId, studentId, targetSlot,
      replacedInstanceId = replaced?.id?.takeIf { it != instanceId },
      traceId = ctx.traceId,
    ).broadcast()
    return EquipResult.Ok(replaced?.id?.takeIf { it != instanceId })
  }

  suspend fun unequip(uid: String, instanceId: String, ctx: GrantContext): UnequipResult {
    val instance = findById(instanceId) ?: return UnequipResult.InstanceMissing
    if (instance.uid != uid) return UnequipResult.NotOwnedByUid
    val equippedBy = instance.equippedBy ?: return UnequipResult.NotEquipped
    val slot = instance.slot ?: return UnequipResult.NotEquipped

    val now = currentDateTime()
    val result = UserEquipmentInstance.withCollection<UserEquipmentInstance, UpdateResult> {
      updateOne(
        Filters.and(
          Filters.eq("_id", instanceId),
          Filters.eq(UserEquipmentInstance::equippedBy.name, equippedBy),
        ),
        Updates.combine(
          Updates.set(UserEquipmentInstance::equippedBy.name, null),
          Updates.set(UserEquipmentInstance::slot.name, null),
          Updates.set(UserEquipmentInstance::updatedAt.name, now),
        ),
      )
    }
    if (!result.modifiedOne()) {
      // 并发卸下已被别人完成
      return UnequipResult.NotEquipped
    }
    EquipmentAuditLog.record(
      uid = uid,
      instanceId = instanceId,
      tplId = instance.tplId,
      kind = EquipmentEventKind.UNEQUIPPED,
      reason = ctx.reason,
      traceId = ctx.traceId,
      before = mapOf("equippedBy" to equippedBy.toString(), "slot" to slot.name),
    )
    EquipmentUnequippedEvent(uid, instanceId, instance.tplId, equippedBy, slot, ctx.traceId).broadcast()
    return UnequipResult.Ok
  }

  /**
   * 确定性强化: 扣材料 (由 [InventoryService.consume] 原子兜底) → 条件 `$inc enhance`.
   *
   * 条件更新以"enhance == 当前等级"做 CAS, 防并发重入同时强化到 +2 (本应需要两批材料).
   * enhance 达到 maxEnhance 时直接返回 AtMaxLevel, 不扣材料.
   */
  suspend fun enhance(uid: String, instanceId: String, ctx: GrantContext): EnhanceResult {
    val instance = findById(instanceId) ?: return EnhanceResult.InstanceMissing
    if (instance.uid != uid) return EnhanceResult.NotOwnedByUid
    val template = ItemTemplateCache.get(instance.tplId) ?: return EnhanceResult.TemplateMissing
    if (template.category != InventoryCategory.EQUIPMENT) return EnhanceResult.NotEquipmentCategory
    val payload = ItemTemplateCache.getEquipmentPayload(instance.tplId) ?: return EnhanceResult.TemplateMissing

    val fromLevel = instance.enhance
    val toLevel = fromLevel + 1
    if (toLevel > payload.maxEnhance) return EnhanceResult.AtMaxLevel
    val materials = payload.enhanceCost.getOrNull(fromLevel) ?: return EnhanceResult.AtMaxLevel

    when (val consume = InventoryService.consume(uid, materials, ctx)) {
      is ConsumeResult.Ok -> Unit
      is ConsumeResult.Insufficient -> return EnhanceResult.Insufficient(consume.shortages)
      is ConsumeResult.DuplicateRequest -> return EnhanceResult.DuplicateRequest(consume.previousTraceId)
      is ConsumeResult.Unsupported -> return EnhanceResult.InventoryFailed(consume.reason)
    }

    val now = currentDateTime()
    val filter: Bson = Filters.and(
      Filters.eq("_id", instanceId),
      Filters.eq(UserEquipmentInstance::uid.name, uid),
      Filters.eq(UserEquipmentInstance::enhance.name, fromLevel),
    )
    val result = UserEquipmentInstance.withCollection<UserEquipmentInstance, UpdateResult> {
      updateOne(
        filter,
        Updates.combine(
          Updates.inc(UserEquipmentInstance::enhance.name, 1),
          Updates.set(UserEquipmentInstance::updatedAt.name, now),
        ),
      )
    }
    if (!result.modifiedOne()) {
      // 并发其他强化路径改了 enhance; 材料已扣, 留痕供人工处理
      Kivotos.logger.warn(
        "enhance: CAS failed after consume uid=$uid inst=$instanceId expect=$fromLevel trace=${ctx.traceId}",
      )
      return EnhanceResult.WriteFailed
    }

    EquipmentAuditLog.record(
      uid = uid,
      instanceId = instanceId,
      tplId = instance.tplId,
      kind = EquipmentEventKind.ENHANCED,
      reason = ctx.reason,
      traceId = ctx.traceId,
      before = mapOf("enhance" to fromLevel.toString()),
      after = mapOf("enhance" to toLevel.toString()),
    )
    EquipmentEnhancedEvent(uid, instanceId, instance.tplId, fromLevel, toLevel, ctx.traceId).broadcast()
    return EnhanceResult.Ok(fromLevel, toLevel)
  }
}

