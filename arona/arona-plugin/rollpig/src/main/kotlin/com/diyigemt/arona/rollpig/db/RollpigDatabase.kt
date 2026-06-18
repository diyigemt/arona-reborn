package com.diyigemt.arona.rollpig.db

import com.diyigemt.arona.rollpig.PluginMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 插件私有的 sqlite 连接(独立于 arona-core 主库), 库文件放在插件 dataFolder 下的 `rollpig.db`。
 *
 * 与 plana 一致: JDBC URL 的 pragma 开启 WAL 与 busy_timeout, 锁竞争时等待而非直接报错;
 * 所有读写经 [dbQuery] 切到 [Dispatchers.IO], 不阻塞事件协程。
 */
internal object RollpigDatabase {
  private val database: Database by lazy {
    val file = PluginMain.resolveDataFile("rollpig.db").apply { parentFile?.mkdirs() }
    val db = Database.connect(
      url = "jdbc:sqlite:${file.absolutePath}?journal_mode=WAL&busy_timeout=5000",
      driver = "org.sqlite.JDBC",
      databaseConfig = DatabaseConfig {
        defaultMaxAttempts = 5
        defaultMinRetryDelay = 100
        defaultMaxRetryDelay = 1000
      }
    )
    transaction(db) {
      SchemaUtils.create(DailyPigTable)
    }
    db
  }

  /** 触发 lazy 初始化(建库建表), 在插件 onLoad 调用一次。 */
  fun init() {
    database
  }

  suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(database) { block() }
  }
}
