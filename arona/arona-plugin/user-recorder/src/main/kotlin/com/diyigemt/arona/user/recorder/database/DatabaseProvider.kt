package com.diyigemt.arona.user.recorder.database

import com.diyigemt.arona.user.recorder.PluginMain
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

internal object DatabaseProvider {
  private val database: Database by lazy {
    val database = Database.connect("jdbc:sqlite:${PluginMain.dataFolder}/contact.db", "org.sqlite.JDBC")
    transaction(database) {
      SchemaUtils.createMissingTablesAndColumns(UserTable, ContactTable, CommandTable)
    }
    database
  }

  suspend fun <T> dbQuerySuspended(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO, database) { block() }

  fun <T> dbQuery(block: () -> T): T = transaction(database) { block() }
}
