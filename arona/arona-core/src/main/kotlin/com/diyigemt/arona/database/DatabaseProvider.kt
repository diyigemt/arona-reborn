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
import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.types.common.RespVer
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
   * 共享的池化 Redis 客户端 (re.this 内部维护连接池, 单实例对并发命令与 pipeline 均安全)。
   *
   * 初始化与关闭在 [redisLifecycleMutex] 内线性化: 首个调用方在锁内建池并发布, 后续走 [sharedRedis] 快路径;
   * 关闭后 [redisClosed] 置位, 任何后续 [redisDbQuery] 直接失败而非重建池 (进程正在退出, 重建无意义且会泄漏)。
   * 构造失败不发布实例, 下次调用自然重试。@Volatile 保证发布/置位对其它线程立即可见。
   */
  @Volatile
  private var sharedRedis: ReThis? = null

  @Volatile
  private var redisClosed: Boolean = false

  private val redisLifecycleMutex = Mutex()

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
   * 获取共享池化客户端: 快路径直接返回已发布的 [sharedRedis]; 慢路径在 [redisLifecycleMutex] 内双重检查后建池,
   * 保证并发首次调用只建一个池。关闭后 ([redisClosed]) 直接拒绝服务、不重建。构造失败不发布、不缓存, 下次重试。
   */
  private suspend fun sharedRedisConnection(): ReThis {
    check(!redisClosed) { "Redis 客户端已关闭, 不再接受新的查询" }
    sharedRedis?.let { return it }
    return redisLifecycleMutex.withLock {
      check(!redisClosed) { "Redis 客户端已关闭, 不再接受新的查询" }
      sharedRedis ?: createPooledRedis().also { sharedRedis = it }
    }
  }

  /**
   * 构造一个内部启用连接池的 [ReThis]。显式锁定 [RespVer.V2] (与历史 kreds 行为一致, 且 RESP2 下 HGETALL /
   * ZRANGE WITHSCORES 维持交替/扁平数组形态, 与门面的解析约定吻合); [db] 在转 Int 前显式做 ULong 范围校验,
   * 避免 [ULong.toInt] 静默截断到错误库。[maxConnections] 收紧到 64, 远低于 re.this 默认的 5000, 防止压垮
   * 服务端 maxclients。注: re.this 构造为延迟建连, 启动成功不代表 Redis 可达; 每条 (含重连的) 池连接都会
   * 应用此处的 db 配置, 不存在 "重连不重做 SELECT" 的问题。
   */
  private fun createPooledRedis(): ReThis {
    val redis = aronaConfig.redis
    require(redis.db <= Int.MAX_VALUE.toULong()) { "redis.db 超出 Int 范围, 无法作为库索引: ${redis.db}" }
    return ReThis(host = redis.host, port = redis.port, protocol = RespVer.V2) {
      db = redis.db.toInt()
      usePooling = true
      maxConnections = 64
    }
  }

  /**
   * Redis 统一访问入口, receiver 为框架门面 [AronaRedis], 调用方不依赖具体客户端类型。
   * 底层为 re.this 池化客户端 (见 [ReThisAronaRedis]), 调用方无需关心连接的获取与归还。
   */
  suspend fun <T> redisDbQuery(block: suspend AronaRedis.() -> T): T =
    block.invoke(ReThisAronaRedis(sharedRedisConnection()))

  /**
   * 幂等关闭共享 Redis 池, 由 [com.diyigemt.arona.AronaApplication] 的 ApplicationStopping 调用。
   * 锁内仅做状态切换 (置 [redisClosed] + 摘除实例), 非挂起的 [ReThis.close] 放到锁外执行, 避免在持锁期间触发
   * 底层阻塞。置位后任何新的 [redisDbQuery] 会被 [sharedRedisConnection] 直接拒绝, 不会重建池。
   *
   * 注: 插件协程是独立根 job (非 app scope 子节点), 框架层没有统一的在途任务 join 点; 故此处不强行等待在途命令——
   * graceful shutdown 时仍在执行的命令允许因池关闭而失败 (调用方各自记日志, 非致命)。
   */
  suspend fun closeRedisConnection() {
    val client = redisLifecycleMutex.withLock {
      if (redisClosed) return@withLock null
      redisClosed = true
      sharedRedis.also { sharedRedis = null }
    }
    client?.close()
  }

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
