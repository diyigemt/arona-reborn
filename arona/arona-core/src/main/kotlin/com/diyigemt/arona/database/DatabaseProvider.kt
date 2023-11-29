package com.diyigemt.arona.database

import com.diyigemt.arona.utils.MongoConfig.Companion.toConnectionString
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.runSuspend
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseProvider {
  private val sqlDatabase: Database by lazy {
    val database = Database.connect("jdbc:sqlite:./database.db", "org.sqlite.JDBC")
    transaction(database) {
      ReflectionUtil.scanTypeAnnotatedObjectInstance(AronaDatabase::class).forEach {
        SchemaUtils.create(it as Table)
      }
    }
    database
  }
  private val noSqlDatabase: MongoDatabase by lazy {
    val serverApi = ServerApi
      .builder()
      .version(ServerApiVersion.V1)
      .build()
    val settings = MongoClientSettings
      .builder()
      .applyConnectionString(ConnectionString(
        aronaConfig.mongodb.toConnectionString()
      ))
      .serverApi(serverApi)
      .build()
    MongoClient.create(settings).getDatabase(aronaConfig.mongodb.db)
  }

  suspend fun <T> sqlDbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO, sqlDatabase) { block() }

  fun <T> sqlDbQuery(block: () -> T): T = transaction(sqlDatabase) { block() }

  fun <T> noSqlDbQuery(block: MongoDatabase.() -> T): T = noSqlDatabase.run(block)

}

@Target(AnnotationTarget.CLASS)
annotation class AronaDatabase
