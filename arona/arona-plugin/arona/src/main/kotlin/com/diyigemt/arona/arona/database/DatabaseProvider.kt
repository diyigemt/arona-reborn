package com.diyigemt.arona.arona.database

import com.diyigemt.arona.arona.tools.ReflectionTool
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.value
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.v1.core.DatabaseConfig as DC
import org.jetbrains.exposed.v1.jdbc.Database as DB
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection

object DatabaseProvider {
  private val database: DB by lazy {
    val database = DB.connect(
      "jdbc:mariadb://${DatabaseConfig.host}/${DatabaseConfig.db}",
      "org.mariadb.jdbc.Driver",
      user = DatabaseConfig.user,
      password = DatabaseConfig.password,
      databaseConfig = DC {
        // Exposed 1.x: defaultRepetitionAttempts → defaultMaxAttempts; 同源
        // defaultMin/MaxRepetitionDelay → defaultMin/MaxRetryDelay (语义不变).
        defaultMaxAttempts = 5
        defaultMinRetryDelay = 1000
        defaultMaxRetryDelay = 5000
      }
    )
    transaction(database) {
      ReflectionTool.scanTypeAnnotatedObjectInstance(Database::class).forEach {
        SchemaUtils.create(it as Table)
      }
    }
    database
  }

  suspend fun <T> dbQueryReadUncommited(block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), database, Connection.TRANSACTION_READ_UNCOMMITTED) { block() }

  suspend fun <T> dbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), database) { block() }

  fun <T> dbQuery(block: () -> T): T = transaction(database) { block() }
}

@Target(AnnotationTarget.CLASS)
annotation class Database

internal object DatabaseConfig : AutoSavePluginData("Database") {
  val host by value("127.0.0.1:3306")
  val db by value("arona-reborn")
  val user by value("arona-reborn")
  val password by value("")
}
