package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.withCollection
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * 单一资源的当前态.
 *
 * - [amount] 当前保有量
 * - [cap] 容量上限 (来自模板 [ItemTemplateDocument.resourceCap] 或默认兜底)
 * - [lastRecoverAt] 上次恢复时间戳, 恢复节律留给后续 ResourceRouter 处理, MVP 只建模字段
 */
@Serializable
data class ResourceState(
  val amount: Long,
  val cap: Long,
  val lastRecoverAt: String,
)

/**
 * 用户仓库文档: 货币 / 资源 / 可堆叠道具三段 map.
 *
 * 为什么用 `Map<String, *>` 而不是 `List<{id,count}>`:
 *  - `$inc currencies.<id>` / `$inc stackables.<id>` 在单 key 上天然原子, 省掉定位数组元素的 `$[]` 特殊写法
 *  - 同一文档内多 key 更新可打包进 bulkWrite 单个 UpdateOneModel, 与单文档 update 等效原子
 *  - BSON key 必须是字符串, 所以这里落库全用 itemId 的十进制字符串, 由 [asItemKey] / [asItemId] 封装转换
 *
 * 装备不落在本文档: 装备实例唯一、状态复杂、换装高频, 单独走 EquipmentService + 独立集合.
 */
@Serializable
data class UserInventoryDocument(
  @BsonId
  val id: String,
  val currencies: Map<String, Long> = CurrencyList.associate { it.asItemKey() to 0L },
  val resources: Map<String, ResourceState> = emptyMap(),
  val stackables: Map<String, Int> = emptyMap(),
  val updatedAt: String = currentDateTime(),
) {
  /** 按模板存储段取当前保有量; 供预检、展示共用, 避免多处重复 key-lookup 的 fallthrough 逻辑. */
  fun amountOf(template: ItemTemplateDocument, itemId: UInt): Long {
    val key = itemId.asItemKey()
    return when (template.category.storage) {
      InventoryStorage.CURRENCY -> currencies[key] ?: 0L
      InventoryStorage.RESOURCE -> resources[key]?.amount ?: 0L
      InventoryStorage.STACKABLE -> (stackables[key] ?: 0).toLong()
      InventoryStorage.EQUIPMENT -> 0L
    }
  }

  companion object : DocumentCompanionObject {
    override val documentName = "UserInventoryDocument"
    override val database get() = KivotosMongoDatabase.instance

    /**
     * 查 uid 对应文档; 不存在时种子创建.
     *
     * 资源段需要从模板读取 cap, 因此只能在此 suspend 流程中组装, 无法塞进 data class 默认值.
     * create 失败若是并发唯一键冲突, 再读一次可直接命中先到者写入的文档.
     */
    suspend fun findOrCreate(uid: String): UserInventoryDocument {
      findOne(uid)?.let { return it }
      return tryInsert(uid) ?: findOne(uid)
        ?: error("inventory create raced but subsequent read still missing: uid=$uid")
    }

    private suspend fun findOne(uid: String): UserInventoryDocument? =
      withCollection<UserInventoryDocument, UserInventoryDocument?> {
        find(idFilter(uid)).limit(1).firstOrNull()
      }

    private suspend fun tryInsert(uid: String): UserInventoryDocument? {
      val doc = UserInventoryDocument(
        id = uid,
        resources = seedResources(),
      )
      return try {
        withCollection<UserInventoryDocument, Unit> { insertOne(doc) }
        doc
      } catch (e: MongoWriteException) {
        // 只把并发唯一键冲突视为 race; 其他写错误原样抛出, 避免把真实故障伪装成并发问题
        if (ErrorCategory.fromErrorCode(e.error.code) == ErrorCategory.DUPLICATE_KEY) null else throw e
      }
    }

    private suspend fun seedResources(): Map<String, ResourceState> {
      val nowStr = currentDateTime()
      return ResourceList.associate { itemId ->
        val cap = ItemTemplateCache.get(itemId)?.resourceCap
          ?: ItemTemplateDocument.DEFAULT_RESOURCE_CAP
        itemId.asItemKey() to ResourceState(amount = 0L, cap = cap, lastRecoverAt = nowStr)
      }
    }
  }
}
