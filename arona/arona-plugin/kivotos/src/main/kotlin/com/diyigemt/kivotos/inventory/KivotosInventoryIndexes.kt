package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.database.withCollection
import com.diyigemt.kivotos.Kivotos
import com.diyigemt.kivotos.inventory.equipment.EquipmentAuditLog
import com.diyigemt.kivotos.inventory.equipment.UserEquipmentInstance
import com.diyigemt.kivotos.inventory.loot.LootTableDocument
import com.diyigemt.kivotos.inventory.market.MarketAuditLog
import com.diyigemt.kivotos.inventory.market.MarketListingDocument
import com.diyigemt.kivotos.inventory.use.UseLog
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document

/**
 * 库存相关 Mongo 索引入口. 在 [com.diyigemt.kivotos.Kivotos.onLoad] 通过
 * `runSuspend { KivotosInventoryIndexes.ensure() }` 调度一次.
 *
 * driver 对已存在的同名/同 key 索引是幂等的, 失败只记日志不抛, 避免启动因索引问题中断.
 */
object KivotosInventoryIndexes {
  suspend fun ensure() {
    ensureAuditIndexes()
    ensureTemplateIndexes()
    ensureUseLogIndexes()
    ensureEquipmentIndexes()
    ensureMarketIndexes()
  }

  private suspend fun ensureAuditIndexes() {
    // 审计 ts 字段存的是字符串形式日期, Mongo TTL 仅对 BSON Date 生效, 故不在此处挂 TTL;
    // 过期清理目前交由运维脚本按月归档.
    runCatching {
      InventoryAuditLog.withCollection<InventoryAuditLog, Unit> {
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(InventoryAuditLog::uid.name),
            Indexes.ascending(InventoryAuditLog::ts.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(InventoryAuditLog::traceId.name),
          IndexOptions().background(true),
        )
      }
    }.onFailure {
      Kivotos.logger.warn("ensure InventoryAuditLog indexes failed: ${it.message}")
    }
  }

  private suspend fun ensureTemplateIndexes() {
    runCatching {
      ItemTemplateDocument.withCollection<ItemTemplateDocument, Unit> {
        createIndex(
          Indexes.ascending(ItemTemplateDocument::category.name),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(ItemTemplateDocument::tags.name),
          IndexOptions().background(true),
        )
      }
    }.onFailure {
      Kivotos.logger.warn("ensure ItemTemplate indexes failed: ${it.message}")
    }
  }

  private suspend fun ensureEquipmentIndexes() {
    runCatching {
      UserEquipmentInstance.withCollection<UserEquipmentInstance, Unit> {
        // 查"某用户的全部装备"/"某学生穿戴"都走它; slot 放最后方便 range / equality 叠加
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(UserEquipmentInstance::uid.name),
            Indexes.ascending(UserEquipmentInstance::equippedBy.name),
            Indexes.ascending(UserEquipmentInstance::slot.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(UserEquipmentInstance::tplId.name),
          IndexOptions().background(true),
        )
        // 最后兜底: 同 uid+student+slot 只能存在一件已穿戴装备.
        // 应用层的 "先 unset 旧 + CAS set 新" 并发场景下仍可能两个请求都通过 CAS (各自拿到不同的 replaced),
        // 这条 partial unique index 会让第二个 CAS set 抛 duplicate key, 被上层 catch 为 WriteFailed.
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(UserEquipmentInstance::uid.name),
            Indexes.ascending(UserEquipmentInstance::equippedBy.name),
            Indexes.ascending(UserEquipmentInstance::slot.name),
          ),
          IndexOptions()
            .name("uniq_equipped_per_slot")
            .background(true)
            .unique(true)
            .partialFilterExpression(
              org.bson.Document("equippedBy", org.bson.Document("\$type", "int"))
            ),
        )
      }
      EquipmentAuditLog.withCollection<EquipmentAuditLog, Unit> {
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(EquipmentAuditLog::uid.name),
            Indexes.ascending(EquipmentAuditLog::ts.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(EquipmentAuditLog::instanceId.name),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(EquipmentAuditLog::traceId.name),
          IndexOptions().background(true),
        )
      }
    }.onFailure {
      // 失败可能有两类: 真实的 Mongo 不可用 (运维问题), 或者 partial unique index 因线上已有重复数据而无法建成.
      // 后者会让 EquipmentService.equip 的并发兜底失效, 因此用 error 级别日志醒目提示
      Kivotos.logger.error("ensure Equipment indexes failed (装备并发约束可能未生效): ${it.message}")
    }
  }

  /**
   * 市场索引.
   *  - postKey unique: 挂牌幂等, 同一次命令网络重试会落到同一 postKey, insertOne 拿 DUPLICATE_KEY 触发回读
   *  - (buyerUid, buyKey) partial unique: 同一买家对同一 listing 的 BUYING/SOLD 记录全局唯一 (防双买)
   *  - (status, itemId, priceUnit ASC): 搜索"某道具最低价"
   *  - (sellerUid, status): "我的挂牌" 列表
   *  - (status, expiresAt ASC): 过期扫描
   */
  private suspend fun ensureMarketIndexes() {
    runCatching {
      MarketListingDocument.withCollection<MarketListingDocument, Unit> {
        createIndex(
          Indexes.ascending(MarketListingDocument::postKey.name),
          IndexOptions()
            .name("uniq_market_post_key")
            .background(true)
            .unique(true),
        )
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(MarketListingDocument::buyerUid.name),
            Indexes.ascending(MarketListingDocument::buyKey.name),
          ),
          IndexOptions()
            .name("uniq_market_buyer_buy_key")
            .background(true)
            .unique(true)
            .partialFilterExpression(Document(MarketListingDocument::buyKey.name, Document("\$type", "string"))),
        )
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(MarketListingDocument::status.name),
            Indexes.ascending(MarketListingDocument::itemId.name),
            Indexes.ascending(MarketListingDocument::priceUnit.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(MarketListingDocument::sellerUid.name),
            Indexes.ascending(MarketListingDocument::status.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(MarketListingDocument::status.name),
            Indexes.ascending(MarketListingDocument::expiresAt.name),
          ),
          IndexOptions().background(true),
        )
      }
      MarketAuditLog.withCollection<MarketAuditLog, Unit> {
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(MarketAuditLog::uid.name),
            Indexes.ascending(MarketAuditLog::ts.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(MarketAuditLog::listingId.name),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(MarketAuditLog::traceId.name),
          IndexOptions().background(true),
        )
      }
    }.onFailure {
      Kivotos.logger.error("ensure Market indexes failed (市场并发约束可能未生效): ${it.message}")
    }
  }

  private suspend fun ensureUseLogIndexes() {
    runCatching {
      UseLog.withCollection<UseLog, Unit> {
        createIndex(
          Indexes.compoundIndex(
            Indexes.ascending(UseLog::uid.name),
            Indexes.ascending(UseLog::ts.name),
          ),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(UseLog::traceId.name),
          IndexOptions().background(true),
        )
        createIndex(
          Indexes.ascending(UseLog::status.name),
          IndexOptions().background(true),
        )
      }
    }.onFailure {
      Kivotos.logger.warn("ensure UseLog indexes failed: ${it.message}")
    }
  }
}
