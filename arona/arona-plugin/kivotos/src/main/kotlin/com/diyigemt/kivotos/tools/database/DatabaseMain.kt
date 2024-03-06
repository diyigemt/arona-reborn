package com.diyigemt.kivotos.tools.database

import com.diyigemt.kivotos.tools.config.MongoDbConfig
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase

private val noSqlDatabase: MongoDatabase by lazy {
  val serverApi = ServerApi
    .builder()
    .version(ServerApiVersion.V1)
    .build()
  val settings = MongoClientSettings
    .builder()
    .applyConnectionString(
      ConnectionString(
        MongoDbConfig.toConnectionString()
      )
    )
    .serverApi(serverApi)
    .build()
  MongoClient.create(settings).getDatabase(MongoDbConfig.db)
}

internal fun <T> noSqlDbQuery(block: MongoDatabase.() -> T): T = block.invoke(noSqlDatabase)
internal suspend fun <T> noSqlDbQuerySuspended(block: suspend MongoDatabase.() -> T): T = block.invoke(noSqlDatabase)

internal interface DocumentCompanionObject {
  val documentName: String
}

internal suspend inline fun <reified T : Any, R> DocumentCompanionObject.withCollection(
  crossinline block: suspend MongoCollection<T>.() -> R,
): R =
  noSqlDbQuerySuspended {
    block(getCollection(documentName))
  }

@Suppress("NOTHING_TO_INLINE")
internal inline fun idFilter(id: String) = Filters.eq("_id", id)
