package com.diyigemt.arona.plana.db

import com.diyigemt.arona.plana.PluginMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 插件私有的 sqlite 连接(独立于 arona-core 的 MariaDB 主库).
 *
 * - 库文件放在插件 dataFolder 下的 `plana.db`.
 * - 通过 JDBC URL 的 pragma 查询参数开启 WAL 与 busy_timeout: xerial 驱动会在每次取连接时套用,
 *   保证并发事件下的写串行化与"锁忙等待"而非直接报错.
 * - 所有读写都经 [dbQuery] 切到 [Dispatchers.IO], 避免阻塞事件协程.
 */
internal object PlanaDatabase {
  private val database: Database by lazy {
    val file = PluginMain.resolveDataFile("plana.db").apply { parentFile?.mkdirs() }
    val db = Database.connect(
      url = "jdbc:sqlite:${file.absolutePath}?journal_mode=WAL&busy_timeout=5000",
      driver = "org.sqlite.JDBC",
      databaseConfig = DatabaseConfig {
        // sqlite 单写者, 锁竞争时自动重试.
        defaultMaxAttempts = 5
        defaultMinRetryDelay = 100
        defaultMaxRetryDelay = 1000
      }
    )
    transaction(db) {
      SchemaUtils.create(SensitiveCacheTable, SeseRankTable, AuditSwitchTable)
    }
    db
  }

  /** 触发 lazy 初始化(建库建表), 在插件 onLoad 调用一次. */
  fun init() {
    database
  }

  suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(database) { block() }
  }
}
