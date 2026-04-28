package com.diyigemt.kivotos.schema

import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.arona.database.DocumentCompanionObject
import com.diyigemt.arona.database.withCollection
import com.diyigemt.kivotos.tools.database.KivotosMongoDatabase
import com.mongodb.client.result.InsertOneResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class ErrorDocument(
  @BsonId
  @SerialName("_id")
  val id: String,
  val code: Int,
  val message: String,
  val timestamp: String,
) {
  companion object : DocumentCompanionObject {
    override val documentName = "ErrorDocument"
    override val database get() = KivotosMongoDatabase.instance
    suspend fun createError(code: Int, message: String): ErrorDocument {
      return ErrorDocument(
        uuid(),
        code,
        message,
        currentDateTime()
      ).also {
        withCollection<ErrorDocument, InsertOneResult> {
          insertOne(it)
        }
      }
    }
  }
}