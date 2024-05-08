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

/**
 * 物品id规则
 *
 * UInt max: 2^32 = 4,294,967,296
 *
 * type id: 1,000,000
 *
 * subtype id: x,000,001
 *
 * 主类型 4,294种
 *
 * 子类型 999,999种
 */
private const val InventoryCategoryBase = 1000000u

private val currencyList = listOf(
  InventoryCategory.COMMON_CURRENCY.toId(0u), // 信用點
  InventoryCategory.COMMON_CURRENCY.toId(1u), // 清輝石
  InventoryCategory.COMMON_CURRENCY.toId(2u), // AP
)

enum class InventoryCategory(val id: UInt) {
  COMMON_CURRENCY(1u); //通用货币 0 信用点 1 清辉石 2 体力

  fun toId(subCategory: UInt) = id * InventoryCategoryBase + subCategory

  companion object {
    private val map = entries.associateBy { it.id }

    /**
     * 根据全限定id
     */
    fun fromId(id: UInt) = checkNotNull(map[id / InventoryCategoryBase])
    fun toSubCategory(id: UInt) = id % InventoryCategoryBase
  }
}

sealed interface Inventory {
  /**
   * 物品的全限定id, 全局唯一
   *
   * 规则与[InventoryCategoryBase]保持一致
   */
  val id: UInt

  /**
   * 物品的主类别
   */
  val category: InventoryCategory

  /**
   * 物品的子类别
   */
  val subCategory: UInt
}

@Serializable
data class InventoryDocument(
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
   * 物品的全限定id, 全局唯一, 与[InventoryDocument.id]保持一致
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

}

/**
 * 用户仓库
 */
@Serializable
data class UserInventoryDocument(
  @BsonId
  val id: String,
  val currencyStorage: MutableList<UserInventoryItem> = currencyList.map {
    UserInventoryItem(it, id)
  }.toMutableList(),
  val storage: MutableList<UserInventoryItem> = mutableListOf(),
) {
  suspend fun increaseInventory(item: UserInventoryItem, delta: Int): Boolean {
    withCollection<UserInventoryDocument, UpdateResult> {
      updateOne(
        filter = Filters.and(
          idFilter(id),
          Filters.eq("${UserInventoryDocument::currencyStorage::name}._id", item.id)
        ),
        update = Updates.inc(
          "${UserInventoryDocument::currencyStorage::name}.$.${UserInventoryItem::count.name}",
          delta
        ),
      )
      updateOne(
        filter = Filters.and(
          idFilter(id),
          Filters.eq("${UserInventoryDocument::storage::name}._id", item.id)
        ),
        update = Updates.inc(
          "${UserInventoryDocument::storage::name}.$.${UserInventoryItem::count.name}",
          delta
        ),
      )
    }
    return true
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
