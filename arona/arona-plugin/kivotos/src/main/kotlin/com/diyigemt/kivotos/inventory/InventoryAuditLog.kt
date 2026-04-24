package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * 库存变更审计流水.
 *
 * 独立成表而非塞回仓库文档, 是为了让仓库主文档只承担"当前态", 补偿、追账、对账
 * 都可围绕 [traceId] / [idempotencyKey] 独立展开. [ts] 存字符串形式日期, Mongo TTL 仅对
 * BSON Date 生效, 故不在此处挂 TTL, 过期清理暂由运维脚本按月归档.
 *
 * _id 用 uuid 字符串而不是 ObjectId, 跟随 kivotos 既有 `@BsonId val id: String` 的风格,
 * 避免引入 kotlinx.serialization 对 ObjectId 的自定义 codec.
 */
@Serializable
data class InventoryAuditLog(
  @BsonId
  val id: String = uuid("inv.log"),
  val uid: String,
  val deltas: List<ItemDelta>,
  val direction: InventoryChangedEvent.Direction,
  val reason: String,
  val sourceType: String,
  val sourceId: String? = null,
  val traceId: String,
  val idempotencyKey: String? = null,
  val ts: String = currentDateTime(),
) {
  companion object : DocumentCompanionObject {
    override val documentName = "InventoryAuditLog"
    override val database get() = KivotosMongoDatabase.instance

    suspend fun record(
      uid: String,
      deltas: List<ItemDelta>,
      direction: InventoryChangedEvent.Direction,
      ctx: GrantContext,
    ) {
      withCollection<InventoryAuditLog, Unit> {
        insertOne(
          InventoryAuditLog(
            uid = uid,
            deltas = deltas,
            direction = direction,
            reason = ctx.reason,
            sourceType = ctx.sourceType,
            sourceId = ctx.sourceId,
            traceId = ctx.traceId,
            idempotencyKey = ctx.idempotencyKey,
          )
        )
      }
    }
  }
}
