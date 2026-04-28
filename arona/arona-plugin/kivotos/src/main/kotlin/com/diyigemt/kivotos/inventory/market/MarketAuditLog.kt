package com.diyigemt.kivotos.inventory.market

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 市场审计动作枚举.
 *
 * [SETTLEMENT_STUCK] 专指"买家已扣款收货但 BUYING→SOLD CAS 失败"这一罕见灾难态 —
 * 需要在 audit 里醒目记录, 以便管理员对照 [com.diyigemt.kivotos.inventory.InventoryAuditLog]
 * 找出买家的 traceId 并手工推进 listing 到 SOLD+PAID.
 */
@Serializable
enum class MarketAuditKind {
  LISTED,
  SOLD,
  SETTLED,
  CANCELLED,
  EXPIRED,
  RETURNED,
  RETURN_FAILED,
  SETTLE_FAILED,
  SETTLEMENT_STUCK,

  /**
   * consume 成功但 listing insert 失败, 反向 grant 也未能确认成功. 此时商品**真的悬空**, 既不在卖家库存
   * 也不在 listing 里. 用 postKey 作为 listingId 兜底, 让管理员能按 postKey + traceId 找回当事数据.
   */
  POST_ORPHAN,
}

/**
 * 市场操作审计. 与 [MarketListingDocument] 分开: listing 描述"当前状态",
 * 这里是"状态迁移的历史轨迹". 查账 / 人工恢复都按 `listingId` 或 `traceId` 检索.
 */
@Serializable
data class MarketAuditLog(
  @SerialName("_id")
  val id: String = uuid("market.log"),
  val uid: String,
  val listingId: String,
  val kind: MarketAuditKind,
  val before: Map<String, String> = emptyMap(),
  val after: Map<String, String> = emptyMap(),
  val reason: String,
  val traceId: String,
  val ts: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "MarketAuditLog"
    override val database get() = KivotosMongoDatabase.instance

    suspend fun record(
      uid: String,
      listingId: String,
      kind: MarketAuditKind,
      reason: String,
      traceId: String,
      before: Map<String, String> = emptyMap(),
      after: Map<String, String> = emptyMap(),
    ) {
      withCollection<MarketAuditLog, Unit> {
        insertOne(
          MarketAuditLog(
            uid = uid,
            listingId = listingId,
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
