package com.diyigemt.kivotos.inventory.use.effect

import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.use.EffectApplyResult
import com.diyigemt.kivotos.inventory.use.ItemEffect
import com.diyigemt.kivotos.inventory.use.SideEffectResult
import com.diyigemt.kivotos.inventory.use.UseRequest
import com.diyigemt.kivotos.inventory.use.UsePreview
import com.diyigemt.kivotos.inventory.use.UseTarget
import com.diyigemt.kivotos.schema.UserDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 好感礼物: 消耗 N 个礼物, 给指定学生加 `favorPerItem * N` 点好感.
 *
 * 好感更新不是库存操作, 不能塞进单文档原子 update, 因此通过 EffectApplyResult.sideEffect
 * 暴露给 UseService 在库存成功后执行. 失败只记 UseLog partial-failed, 不回滚库存 —
 * 自动补偿链路本身也可能失败, 脏账风险更大.
 */
object FavorGiftEffect : ItemEffect {
  override val key: String = "favor_gift"
  override val requiresTarget: Boolean = true
  override val supportsBatch: Boolean = true

  override fun supports(target: UseTarget): Boolean = target is UseTarget.Student

  override suspend fun preview(req: UseRequest): UsePreview {
    val payload = decode(req.template.effectPayload)
    val studentId = (req.target as UseTarget.Student).id
    val favorGain = payload.favorPerItem * req.count
    return UsePreview(
      consumes = listOf(ItemDelta(req.template.id, req.count.toLong())),
      grants = emptyList(),
      summary = "赠送 ${req.template.name} x${req.count}",
      extraNotes = listOf("学生[$studentId] 好感 +$favorGain"),
    )
  }

  override suspend fun apply(req: UseRequest, ctx: GrantContext): EffectApplyResult {
    val preview = preview(req)
    val payload = decode(req.template.effectPayload)
    val studentId = (req.target as UseTarget.Student).id
    val favorGain = payload.favorPerItem * req.count
    return EffectApplyResult(
      consumes = preview.consumes,
      grants = preview.grants,
      narrative = preview.extraNotes,
      sideEffect = {
        val user = UserDocument.findUserOrCreate(req.uid)
        user.updateStudentFavor(studentId, favorGain)
        SideEffectResult(details = mapOf("favorGain" to favorGain.toString(), "student" to studentId.toString()))
      },
    )
  }

  private fun decode(payload: JsonElement?): Payload {
    require(payload != null) { "FavorGiftEffect 需要 effectPayload" }
    return json.decodeFromJsonElement(Payload.serializer(), payload)
  }

  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Payload(val favorPerItem: Int)
}
