package com.diyigemt.kivotos.inventory

import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import com.mongodb.client.result.InsertOneResult
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class InventoryExcelTable(
  /**
   * [InventoryCategoryBase]为类别id
   *
   * 剩余6位为子类递增id
   *
   */
  @BsonId
  override val id: UInt,
  override val category: InventoryCategory,
  override val subCategory: UInt,
) : Inventory {
  constructor(id: UInt) : this(
    id,
    InventoryCategory.fromId(id),
    InventoryCategory.toSubCategory(id)
  )

  companion object : DocumentCompanionObject {
    override val documentName = "InventoryDocument"
  }
}

@Serializable
data class UserInventoryItem(
  /**
   * 物品的全限定id, 全局唯一, 与[InventoryExcelTable.id]保持一致
   */
  override val id: UInt,
  /**
   * 用户uid
   */
  val uid: String,
  /**
   * 保有数量
   */
  val count: Int = 0,
) : Inventory {
  override val category by lazy {
    InventoryCategory.fromId(id)
  }
  override val subCategory by lazy {
    InventoryCategory.toSubCategory(id)
  }
  suspend fun increaseInventory(delta: Int): Boolean {
    UserInventoryDocument.withCollection<UserInventoryDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(
          idFilter(uid),
          Filters.eq("${UserInventoryDocument::currencyStorage::name}._id", id)
        ),
        update = Updates.inc(
          "${UserInventoryDocument::currencyStorage::name}.$.${UserInventoryItem::count.name}",
          delta
        ),
      )
      updateOne(
        filter = Filters.and(
          idFilter(uid),
          Filters.eq("${UserInventoryDocument::storage::name}._id", id)
        ),
        update = Updates.inc(
          "${UserInventoryDocument::storage::name}.$.${UserInventoryItem::count.name}",
          delta
        ),
      )
    }
    return true
  }

}

/**
 * 用户仓库
 */
@Serializable
data class UserInventoryDocument(
  @BsonId
  val id: String,
  val currencyStorage: MutableList<UserInventoryItem> = CurrencyList.map {
    UserInventoryItem(it, id)
  }.toMutableList(),
  val storage: MutableList<UserInventoryItem> = mutableListOf(),
) {
  suspend fun increaseInventory(item: UserInventoryItem, delta: Int): Boolean {
    return item.increaseInventory(delta)
  }
  companion object : DocumentCompanionObject {
    override val documentName = "UserInventoryDocument"
    private suspend fun createUserInventoryDocument(uid: String): UserInventoryDocument {
      return UserInventoryDocument(uid).also {
        withCollection<UserInventoryDocument, InsertOneResult> {
          insertOne(it)
        }
      }
    }
    suspend fun findUserInventoryOrCreate(uid: String) : UserInventoryDocument {
      return withCollection<UserInventoryDocument, UserInventoryDocument?> {
        find<UserInventoryDocument>(
          filter = idFilter(uid)
        ).limit(1).firstOrNull()
      } ?: createUserInventoryDocument(uid)
    }
  }
}
