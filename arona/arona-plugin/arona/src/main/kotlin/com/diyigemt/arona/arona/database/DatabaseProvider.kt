package com.diyigemt.arona.arona.database

import com.diyigemt.arona.arona.Arona
import com.diyigemt.arona.arona.tools.ReflectionTool
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.Database as DB
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig

object DatabaseProvider {
  private val database: DB by lazy {
    val database = DB.connect(
      "jdbc:sqlite:${Arona.dataFolder}/arona.db",
      "org.sqlite.JDBC",
      setupConnection = {
        SQLiteConfig().apply {
          busyTimeout = 2000
          apply(it)
        }
      },
      databaseConfig = DatabaseConfig {
        defaultRepetitionAttempts = 5
        defaultMinRepetitionDelay = 1000
        defaultMaxRepetitionDelay = 5000
      }
    )
    transaction(database) {
      ReflectionTool.scanTypeAnnotatedObjectInstance(Database::class).forEach {
        SchemaUtils.create(it as Table)
      }
    }
    database
  }

  suspend fun <T> dbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(currentCoroutineContext(), database) { block() }

  fun <T> dbQuery(block: () -> T): T = transaction(database) { block() }
}

@Target(AnnotationTarget.CLASS)
annotation class Database
