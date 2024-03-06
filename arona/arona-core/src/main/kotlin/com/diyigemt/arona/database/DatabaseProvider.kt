package com.diyigemt.arona.database

import com.diyigemt.arona.database.DatabaseProvider.noSqlDbQuerySuspended
import com.diyigemt.arona.utils.MongoConfig.Companion.toConnectionString
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.runSuspend
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.client.model.Filters
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.github.crackthecodeabhi.kreds.connection.Endpoint
import io.github.crackthecodeabhi.kreds.connection.KredsClient
import io.github.crackthecodeabhi.kreds.connection.newClient
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig

object DatabaseProvider {
  private val sqlDatabase: Database by lazy {
    val database = Database.connect(
      "jdbc:sqlite:./database.db",
      "org.sqlite.JDBC",
      setupConnection = {
        SQLiteConfig().apply {
          busyTimeout = 1000
          apply(it)
        }
      },
      databaseConfig = DatabaseConfig {
        defaultRepetitionAttempts = 3
        defaultMinRepetitionDelay = 0
        defaultMaxRepetitionDelay = 1500
      }
    )
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
      .applyConnectionString(
        ConnectionString(
          aronaConfig.mongodb.toConnectionString()
        )
      )
      .serverApi(serverApi)
      .build()
    MongoClient.create(settings).getDatabase(aronaConfig.mongodb.db)
  }
  private val redisDatabase: KredsClient by lazy {
    newClient(Endpoint(aronaConfig.redis.host, aronaConfig.redis.port)).apply {
      runSuspend {
        select(aronaConfig.redis.db)
      }
    }
  }

  internal fun <T> sqlDbQuery(block: () -> T): T = transaction(sqlDatabase) { block() }

  internal suspend fun <T> sqlDbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), sqlDatabase) { block() }

  internal fun <T> noSqlDbQuery(block: MongoDatabase.() -> T): T = block.invoke(noSqlDatabase)
  internal suspend fun <T> noSqlDbQuerySuspended(block: suspend MongoDatabase.() -> T): T = block.invoke(noSqlDatabase)

  suspend fun <T> redisDbQuery(block: suspend KredsClient.() -> T) = block.invoke(redisDatabase)

}

@Target(AnnotationTarget.CLASS)
internal annotation class AronaDatabase

internal enum class RedisPrefixKey(val prefix: String) {
  WEB_LOGIN("login"),
  WEB_TOKEN("token"),
  WEB_BINDING("bind");

  companion object {
    fun buildKey(type: RedisPrefixKey, key: String) = "${type.prefix}.$key"
  }
}

internal interface DocumentCompanionObject {
  val documentName: String
}

internal suspend inline fun <reified T : Any, R> DocumentCompanionObject.withCollection(
  crossinline block: suspend MongoCollection<T>.() -> R,
): R =
  noSqlDbQuerySuspended {
    block(getCollection(documentName))
  }

internal fun <T : Any> MongoCollection<T>.idFilter(id: String) = Filters.eq("_id", id)
