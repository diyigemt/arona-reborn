package com.diyigemt.kivotos.schema

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.deletedOne
import com.diyigemt.arona.database.idFilter
import com.diyigemt.arona.database.withCollection
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.client.result.DeleteResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class ShrimpHeadLevelExcelTable(
  @BsonId
  @SerialName("_id")
  val level: Int, // 对应好感等级
  val sum: Int, // 累积值
  val next: Int, // 下一级值
) {

  companion object : DocumentCompanionObject {
    override val documentName = "ShrimpHeadLevelExcelTable"
    override val database get() = KivotosMongoDatabase.instance
    suspend fun findLevel(current: Int): ShrimpHeadLevelExcelTable {
      return withCollection<ShrimpHeadLevelExcelTable, ShrimpHeadLevelExcelTable> {
        find(
          filter = Filters.lte(ShrimpHeadLevelExcelTable::sum.name, current)
        )
          .sort(Sorts.descending(ShrimpHeadLevelExcelTable::sum.name))
          .limit(1)
          .first()
      }
    }
    suspend fun deleteRecord(uid: String): Boolean {
      return withCollection<ShrimpHeadLevelExcelTable, DeleteResult> {
        deleteOne(
          filter = idFilter(uid)
        )
      }.deletedOne()
    }
  }
}
