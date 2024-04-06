package com.diyigemt.kivotos.schema

import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.uuid
import com.diyigemt.kivotos.tools.database.DocumentCompanionObject
import com.diyigemt.kivotos.tools.database.withCollection
import com.mongodb.client.result.InsertOneResult
import org.bson.codecs.pojo.annotations.BsonId

data class ErrorDocument(
  @BsonId
  val id: String,
  val code: Int,
  val message: String,
  val timestamp: String,
) {
  companion object : DocumentCompanionObject {
    override val documentName = "ErrorDocument"
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