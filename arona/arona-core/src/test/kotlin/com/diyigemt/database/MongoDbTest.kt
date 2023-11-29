package com.diyigemt.database

import com.diyigemt.arona.database.DatabaseProvider.noSqlDbQuery
import kotlin.test.Test

class MongoDbTest {
  data class User(
    val username: String
  )
  @Test
  fun testConnection() {
    noSqlDbQuery {

    }
  }
}
