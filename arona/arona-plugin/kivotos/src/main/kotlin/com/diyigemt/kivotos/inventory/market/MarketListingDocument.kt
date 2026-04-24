package com.diyigemt.kivotos.inventory.market

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * 挂牌状态机.
 *
 * 正常流转:
 *  ACTIVE --(buyer 点击)--> BUYING --(buyer execute OK)--> SOLD
 *  ACTIVE --(seller 取消)--> CANCELLING --(grant OK)--> CANCELLED
 *  ACTIVE --(过期扫描 CAS)--> EXPIRING --(grant OK)--> EXPIRED
 *
 * 异常分支:
 *  CANCELLING/EXPIRING --(grant 失败)--> RETURN_FAILED, 由 seller 手动 /重发 重试
 *  BUYING --(execute 失败)--> ACTIVE (原位回滚), 买家重试即可
 *  BUYING --(execute OK 但 CAS SOLD 失败: 极端进程崩溃)--> 留 BUYING, 由管理员命令对照 InventoryAuditLog 兜底 (MVP 不做)
 */
@Serializable
enum class MarketStatus {
  ACTIVE,
  BUYING,
  SOLD,
  CANCELLING,
  CANCELLED,
  EXPIRING,
  EXPIRED,
  RETURN_FAILED,
}

/**
 * SOLD 之后的卖家收款子状态机 (status=SOLD 时才有意义).
 *
 * PENDING --(seller settle)--> PAYING --(grant OK)--> PAID
 * PAYING --(grant 结构化失败)--> PENDING (保留 settleKey, 可再次 settle; Redis TTL 到期后安全)
 */
@Serializable
enum class SettlementStatus {
  NOT_APPLICABLE,
  PENDING,
  PAYING,
  PAID,
  FAILED,
}

/**
 * 挂牌文档. 单 listing 作为 saga 的 durable ledger: 状态 + idempotency key 都落在这一份上.
 *
 * **字段契约**:
 *  - [postKey] unique index. 由命令层生成, 网络超时重试的同一次命令共享同一 postKey,
 *    insertOne 拿 DUPLICATE_KEY 后服务端回读既有 listing 返回 Ok (详见 [MarketService.post]).
 *  - [buyKey] 每次从 ACTIVE 进入 BUYING 时重新生成; 作为 InventoryService.execute 的
 *    idempotencyKey 派生键. 回到 ACTIVE 时清空.
 *  - [settleKey] 每次进入 PAYING 时重新生成, 保证跨进程 settle 幂等且新 key 不会撞 Redis 占位.
 *  - [returnAttempt] 首次入 CANCELLING/EXPIRING 设为 1; RETURN_FAILED 重试时 CAS 自增,
 *    派生 grant key 随之变化 (market.return.<id>.<attempt>), 所以 Redis 占位不会毒化重试.
 *  - [returnTarget] 记录返还成功后应该转到的终态 (CANCELLED / EXPIRED), 让 [MarketService.retryReturn]
 *    无需区分入口是 cancel 还是 expire.
 *  - [expiresAt] / [createdAt] / [updatedAt] 复用项目 `currentDateTime()` 的字符串格式
 *    "yyyy-MM-dd HH:mm:ss", 字典序等同时间序, 支持 `$lt` / `$gt` 直接比较.
 */
@Serializable
data class MarketListingDocument(
  @BsonId
  @SerialName("_id")
  val id: String = uuid("market"),
  val sellerUid: String,
  val itemId: UInt,
  val count: Int,
  val priceItemId: UInt,
  val priceUnit: Long,
  val totalPrice: Long,
  val status: MarketStatus,
  val buyerUid: String? = null,
  val buyKey: String? = null,
  val lockExpiresAt: String? = null,
  val settlementStatus: SettlementStatus = SettlementStatus.NOT_APPLICABLE,
  val settleKey: String? = null,
  val settledAt: String? = null,
  val returnAttempt: Int = 0,
  val returnTarget: MarketStatus? = null,
  val postKey: String,
  val expiresAt: String,
  val createdAt: String = currentDateTime(),
  val updatedAt: String = currentDateTime(),
  val traceId: String,
) {
  companion object : DocumentCompanionObject {
    override val documentName = "MarketListing"
    override val database get() = KivotosMongoDatabase.instance
  }
}
