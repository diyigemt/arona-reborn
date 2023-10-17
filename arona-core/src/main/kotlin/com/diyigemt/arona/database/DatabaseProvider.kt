package com.diyigemt.arona.database

import com.diyigemt.arona.utils.ReflectionUtil
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseProvider {
  private val database: Database by lazy {
    val database = Database.connect("jdbc:sqlite:./database.db", "org.sqlite.JDBC")
    transaction(database) {
      ReflectionUtil.scanTypeAnnotatedObjectInstance(AronaBackendDatabase::class).forEach {
        SchemaUtils.create(it as Table)
      }
    }
    database
  }

  suspend fun <T> dbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO, database) { block() }

  fun <T> dbQuery(block: () -> T): T = transaction(database) { block() }
}

annotation class AronaBackendDatabase
