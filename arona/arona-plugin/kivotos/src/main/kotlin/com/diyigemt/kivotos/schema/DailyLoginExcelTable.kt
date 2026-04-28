package com.diyigemt.kivotos.schema

import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class DailyLoginExcelTable(
  @BsonId
  @SerialName("_id")
  val id: Int // 第几天登录就获得第几天的东西
) {
  companion object : DocumentCompanionObject {
    override val documentName = "DailyLoginExcelTable"
    override val database get() = KivotosMongoDatabase.instance
  }
}
