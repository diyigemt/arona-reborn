package com.diyigemt.kivotos.schema

import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.idFilter
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class FavorLevelExcelTable(
  @BsonId
  val level: Int,
  val sum: Int, // 累积值
  val next: Int, // 下一级值
) {

  companion object : DocumentCompanionObject {
    override val documentName = "FavorLevelExcelTable"
    suspend fun findLevel(current: Int): FavorLevelExcelTable {
      return withCollection<FavorLevelExcelTable, FavorLevelExcelTable> {
        find(
          filter = Filters.lte(FavorLevelExcelTable::sum.name, current)
        )
          .sort(Sorts.descending(FavorLevelExcelTable::sum.name))
          .limit(1)
          .first()
      }
    }
    suspend fun deleteRecord(uid: String): Boolean {
      return withCollection<FavorLevelExcelTable, DeleteResult> {
        deleteOne(
          filter = idFilter(uid)
        )
      }.deletedCount == 1L
    }
  }
}
