package com.diyigemt.kivotos.inventory

import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.result.InsertOneResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import java.rmi.server.UID

private val currencyList = listOf(
  InventoryCategory.COMMON_CURRENCY.toId(0u), // 信用點
  InventoryCategory.COMMON_CURRENCY.toId(1u), // 清輝石
  InventoryCategory.COMMON_CURRENCY.toId(2u), // AP
)

enum class InventoryCategory(val id: Int) {
  COMMON_CURRENCY(0); //通用货币 0 信用点 1 清辉石 2 体力

  fun toId(subCategory: UInt) = (id.toUInt() shl 20) and (subCategory and 0xFFFFF.toUInt())

  companion object {
    private val map = entries.associateBy { it.id }
    fun fromId(id: Int) = checkNotNull(map[id])
  }
}

sealed interface Inventory {
  val id: UInt
  val category: InventoryCategory
  val subCategory: UInt
}

@Serializable
data class InventoryDocument(
  /**
   * kotlin 32 bit
   *
   * high 12bit type
   *
   * low 20bit growth subtype
   */
  @BsonId
  override val id: UInt,
  override val category: InventoryCategory,
  override val subCategory: UInt,
) : Inventory {
  constructor(id: UInt) : this(
    id,
    InventoryCategory.fromId((id shr 20).toInt()),
    id and 0xFFFFF.toUInt()
  )

  companion object : DocumentCompanionObject {
    override val documentName = "InventoryDocument"
  }
}

@Serializable
data class UserInventoryItem(
  override val id: UInt,
  val uid: String,
) : Inventory {
  override val category by lazy {
    InventoryCategory.fromId((id shr 20).toInt())
  }
  override val subCategory by lazy {
    id and 0xFFFFF.toUInt()
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
