package com.diyigemt.kivotos.tools.database

import com.diyigemt.kivotos.tools.config.MongoDbConfig
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase

/**
 * kivotos 独立的 [MongoDatabase]. 指向同一 Mongo 实例下由 [MongoDbConfig] 配置的 db,
 * 使用插件自己的 user/password 认证 (通过 `?authSource=$db`), 与 arona 主库相互独立.
 *
 * 只维护一个 [MongoClient] 连接池; 所有 kivotos 的 [com.diyigemt.arona.database.DocumentCompanionObject]
 * 实现应通过 override `database` 指向 [instance].
 */
internal object KivotosMongoDatabase {
  val instance: MongoDatabase by lazy {
    val settings = MongoClientSettings.builder()
      .applyConnectionString(ConnectionString(MongoDbConfig.toConnectionString()))
      .serverApi(ServerApi.builder().version(ServerApiVersion.V1).build())
      .build()
    MongoClient.create(settings).getDatabase(MongoDbConfig.db)
  }
}
