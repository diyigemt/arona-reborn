package com.diyigemt.arona.database

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
import java.sql.Connection

object DatabaseProvider {
  private val sqlDatabase: Database by lazy {
    val database = Database.connect(
      "jdbc:mariadb://${aronaConfig.mariadb.host}/${aronaConfig.mariadb.db}",
      "org.mariadb.jdbc.Driver",
      user = aronaConfig.mariadb.user,
      password = aronaConfig.mariadb.password,
      databaseConfig = DatabaseConfig {
        defaultRepetitionAttempts = 5
        defaultMinRepetitionDelay = 1000
        defaultMaxRepetitionDelay = 5000
      }
    )
    transaction(database) {
      ReflectionUtil.scanTypeAnnotatedObjectInstance(AronaDatabase::class).forEach {
        SchemaUtils.create(it as Table)
      }
    }
    database
  }
  /**
   * Arona 主库. 仅 core 默认 [DocumentCompanionObject] 使用; 其它模块 (例如 kivotos) 若连接到同一 Mongo 实例的不同 db,
   * 应通过 override [DocumentCompanionObject.database] 指向自己的 [MongoDatabase].
   */
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

  suspend fun <T> sqlDbQueryReadUncommited(block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), sqlDatabase, Connection.TRANSACTION_READ_UNCOMMITTED) { block() }

  suspend fun <T> sqlDbQueryWithIsolation(isolationLevel: Int, block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), sqlDatabase, isolationLevel) { block() }


  /**
   * 默认 MongoDatabase (arona 主库) 的公开入口, 供 [DocumentCompanionObject.database] 默认 getter 使用.
   * 不直接暴露底层 lazy 属性, 避免把存储细节固化为硬 ABI.
   */
  val defaultMongoDatabase: MongoDatabase get() = noSqlDatabase

  /** 幂等地为 Mongo 集合建立索引; 已存在的同名索引由 driver 静默跳过, 失败仅 log warn. */
  suspend fun ensureMongoIndexes() = MongoIndexes.ensure(noSqlDatabase)

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

interface DocumentCompanionObject {
  val documentName: String

  /**
   * 该 document 所属的 [MongoDatabase]. 默认指向 arona 主库;
   * 如果模块需要连接到同一 Mongo 实例上的其他 db (如 kivotos 的独立数据库),
   * 可在 companion object 中 override 本字段指向自己的 [MongoDatabase].
   */
  val database: MongoDatabase get() = DatabaseProvider.defaultMongoDatabase
}

suspend inline fun <reified T : Any, R> DocumentCompanionObject.withCollection(
  crossinline block: suspend MongoCollection<T>.() -> R,
): R = block(database.getCollection(documentName))

fun <T : Any> MongoCollection<T>.idFilter(id: String) = Filters.eq("_id", id)
