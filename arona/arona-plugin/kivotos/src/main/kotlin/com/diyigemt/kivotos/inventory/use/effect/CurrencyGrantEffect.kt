package com.diyigemt.kivotos.inventory.use.effect

import com.diyigemt.kivotos.inventory.GrantContext
import com.diyigemt.kivotos.inventory.ItemDelta
import com.diyigemt.kivotos.inventory.use.EffectApplyResult
import com.diyigemt.kivotos.inventory.use.ItemEffect
import com.diyigemt.kivotos.inventory.use.UseRequest
import com.diyigemt.kivotos.inventory.use.UsePreview
import com.diyigemt.kivotos.inventory.use.UseTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 纯确定性 effect: 消耗 N 个当前道具, 按模板 payload 中声明的 grants 列表发放对应倍数的产出.
 * payload 形如 `{"grants":[{"itemId":1000000,"amount":100}]}`; 经典用例是"体力药" / "钻石袋".
 */
object CurrencyGrantEffect : ItemEffect {
  override val key: String = "currency_grant"
  override val requiresTarget: Boolean = false
  override val supportsBatch: Boolean = true

  override fun supports(target: UseTarget): Boolean = target is UseTarget.None

  override suspend fun preview(req: UseRequest): UsePreview {
    val payload = decode(req.template.effectPayload)
    val grants = payload.grants.map { ItemDelta(it.itemId, it.amount * req.count) }
    val consumes = listOf(ItemDelta(req.template.id, req.count.toLong()))
    return UsePreview(
      consumes = consumes,
      grants = grants,
      summary = "使用 ${req.template.name} x${req.count}",
      extraNotes = grants.map { "获得 itemId=${it.itemId} x${it.amount}" },
    )
  }

  override suspend fun apply(req: UseRequest, ctx: GrantContext): EffectApplyResult {
    val preview = preview(req)
    return EffectApplyResult(
      consumes = preview.consumes,
      grants = preview.grants,
      narrative = preview.extraNotes,
    )
  }

  private fun decode(payload: JsonElement?): Payload {
    require(payload != null) { "CurrencyGrantEffect 需要 effectPayload" }
    return json.decodeFromJsonElement(Payload.serializer(), payload)
  }

  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class Payload(val grants: List<ItemDelta>)
}
