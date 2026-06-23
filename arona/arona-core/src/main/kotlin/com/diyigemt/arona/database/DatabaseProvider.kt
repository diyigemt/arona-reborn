package com.diyigemt.arona.database

import com.diyigemt.arona.utils.MongoConfig.Companion.toConnectionString
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.utils.aronaConfig
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

object DatabaseProvider {
  private val sqlDatabase: Database by lazy {
    val database = Database.connect(
      "jdbc:mariadb://${aronaConfig.mariadb.host}/${aronaConfig.mariadb.db}",
      "org.mariadb.jdbc.Driver",
      user = aronaConfig.mariadb.user,
      password = aronaConfig.mariadb.password,
      databaseConfig = DatabaseConfig {
        // Exposed 1.x 重命名: defaultRepetitionAttempts → defaultMaxAttempts,
        // defaultMin/MaxRepetitionDelay → defaultMin/MaxRetryDelay (语义不变).
        defaultMaxAttempts = 5
        defaultMinRetryDelay = 1000
        defaultMaxRetryDelay = 5000
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
      .applyAronaCodecs()
      .serverApi(serverApi)
      .build()
    MongoClient.create(settings).getDatabase(aronaConfig.mongodb.db)
  }
  /**
   * 共享 Redis 连接 (单条 TCP), 经 [redisDbQuery] 暴露给实时消息路径等高频调用方。
   *
   * 仅在 [select] 成功返回后才发布到此字段: 旧实现用 `by lazy` + fire-and-forget 的 `runSuspend { select }`,
   * lazy 立即返回连接而 SELECT 在另一协程异步执行且不被 await, 导致首批命令可能在切库前发出、落到错误的 db
   * (默认 db0)。改为 @Volatile + [sharedRedisInitMutex] 守护的一次性 suspend 初始化 (见 [sharedRedisConnection]),
   * 消除该库选择竞态。@Volatile 保证发布后的连接对其它线程立即可见。
   */
  @Volatile
  private var sharedRedis: KredsClient? = null
  private val sharedRedisInitMutex = Mutex()

  internal fun <T> sqlDbQuery(block: () -> T): T = transaction(sqlDatabase) { block() }

  internal suspend fun <T> sqlDbQuerySuspended(block: suspend () -> T): T =
    suspendTransaction(sqlDatabase) { block() }

  suspend fun <T> sqlDbQueryReadUncommited(block: suspend () -> T): T =
    suspendTransaction(sqlDatabase, transactionIsolation = Connection.TRANSACTION_READ_UNCOMMITTED) { block() }

  suspend fun <T> sqlDbQueryWithIsolation(isolationLevel: Int, block: suspend () -> T): T =
    suspendTransaction(sqlDatabase, transactionIsolation = isolationLevel) { block() }


  /**
   * 默认 MongoDatabase (arona 主库) 的公开入口, 供 [DocumentCompanionObject.database] 默认 getter 使用.
   * 不直接暴露底层 lazy 属性, 避免把存储细节固化为硬 ABI.
   */
  val defaultMongoDatabase: MongoDatabase get() = noSqlDatabase

  /** 幂等地为 Mongo 集合建立索引; 已存在的同名索引由 driver 静默跳过, 失败仅 log warn. */
  suspend fun ensureMongoIndexes() = MongoIndexes.ensure(noSqlDatabase)

  /**
   * 创建一条已切换到配置库的独立 Redis 连接, 供后台批处理 (如 DAU 归档) 独占使用。
   *
   * 与 [redisDbQuery] 使用的共享连接隔离: 各自持有独立的回复队列, 批处理协程被取消/超时也只影响自己的连接,
   * 不会错位实时消息路径的回复 (kreds 单连接靠 per-client mutex 配对"写+读", 一旦某协程在 read 中被取消
   * 留下孤儿回复, 该连接后续回复会永久偏移)。调用方独占该连接并负责 [KredsClient.close]。
   */
  suspend fun newRedisConnection(): KredsClient = connectAndSelect()

  /**
   * 建立一条 Redis 连接并 await 其 SELECT 到配置库后才返回。初始化失败 (含取消) 时关闭尚未交付的连接,
   * 并把关闭过程的异常作为 suppressed 附加到原异常上, 避免连接泄漏又不掩盖根因。
   */
  private suspend fun connectAndSelect(): KredsClient {
    val redis = aronaConfig.redis
    val client = newClient(Endpoint(redis.host, redis.port))
    return try {
      client.select(redis.db)
      client
    } catch (e: Throwable) {
      runCatching { client.close() }.exceptionOrNull()?.let(e::addSuppressed)
      throw e
    }
  }

  /**
   * 获取共享 Redis 连接, 仅初始化一次。快路径直接读已发布的 [sharedRedis]; 慢路径在 [sharedRedisInitMutex]
   * 内双重检查后 [connectAndSelect], 确保连接在 SELECT 成功前不会被任何调用方看到, 且并发首次调用只建一条连接。
   * 初始化失败不发布、不缓存, 下次调用自然重试。
   */
  private suspend fun sharedRedisConnection(): KredsClient {
    sharedRedis?.let { return it }
    return sharedRedisInitMutex.withLock {
      sharedRedis ?: connectAndSelect().also { sharedRedis = it }
    }
  }

  /**
   * Redis 统一访问入口, receiver 为框架门面 [AronaRedis], 调用方不再依赖具体客户端类型。
   * 当前底层仍是 kreds 共享连接 (见 [KredsAronaRedis]); 后续可整体替换实现而不动任何调用点。
   */
  suspend fun <T> redisDbQuery(block: suspend AronaRedis.() -> T): T =
    block.invoke(KredsAronaRedis(sharedRedisConnection()))

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
