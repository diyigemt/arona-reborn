package com.diyigemt.arona.database

import com.diyigemt.arona.database.permission.ContactDocument
import com.diyigemt.arona.database.permission.MongoContactDocument
import com.diyigemt.arona.database.permission.UserDocument
import com.diyigemt.arona.utils.commandLineLogger
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase

internal object MongoIndexes {
  suspend fun ensure(database: MongoDatabase) {
    val contact = database.getCollection<MongoContactDocument>("Contact")
    val user = database.getCollection<UserDocument>("User")
    ensureAscending(contact, "${ContactDocument::members.name}._id")
    ensureAscending(contact, ContactDocument::contactType.name)
    ensureAscending(user, UserDocument::uid.name)
    ensureAscending(user, UserDocument::contacts.name)
  }

  /**
   * 不指定 index name, 由 driver 按 `<field>_1` 规则生成; 同 key 索引已存在时 driver 幂等成功,
   * 从而避免"同 key 不同 name"的冲突报错.
   */
  private suspend fun <T : Any> ensureAscending(
    collection: MongoCollection<T>,
    field: String,
  ) {
    runCatching {
      collection.createIndex(Indexes.ascending(field), IndexOptions().background(true))
    }.onFailure {
      commandLineLogger.warn("ensure mongo ascending index on ${collection.namespace}.$field failed: ${it.message}")
    }
  }
}
