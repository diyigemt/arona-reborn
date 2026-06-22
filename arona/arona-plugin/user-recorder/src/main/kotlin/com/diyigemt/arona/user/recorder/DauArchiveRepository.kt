@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.diyigemt.arona.user.recorder

import com.diyigemt.arona.utils.now
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import com.mongodb.ServerApi
import com.mongodb.ServerApiVersion
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.flow.firstOrNull
import org.bson.Document
import java.util.Date

/**
 * 归档存储不可用: 未启用、连接失败或读写异常。用于把 "确实没有该日数据" 与 "存储故障" 区分开,
 * 避免后者被上层当成 DAU = 0 展示。
 */
internal class ArchiveUnavailableException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)

private const val ArchiveSchemaVersion = 1

/**
 * DAU 每日摘要的 MongoDB 归档库。仅存聚合计数 (受命令集合规模约束, 实践中很小), 远不触 16MiB 单文档上限。
 *
 * - MongoClient 懒加载: 首次归档/读取时才建连接池, [close] 在插件协程结束时释放。
 * - 全程使用 [org.bson.Document] + 驱动默认 codec, 不依赖 bson-kotlinx / 序列化插件。
 * - 凭据通过 [MongoCredential] 传入, 不做 URI 字符串拼接, 规避用户名/密码中的特殊字符问题。
 */
internal object DauArchiveRepository {
  private val clientHandle = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val builder = MongoClientSettings.builder()
      .applyToClusterSettings { it.hosts(listOf(ServerAddress(ArchiveConfig.host, ArchiveConfig.port))) }
      .serverApi(ServerApi.builder().version(ServerApiVersion.V1).build())
    if (ArchiveConfig.user.isNotBlank()) {
      builder.credential(
        MongoCredential.createCredential(
          ArchiveConfig.user,
          ArchiveConfig.effectiveAuthSource,
          ArchiveConfig.password.toCharArray(),
        ),
      )
    }
    MongoClient.create(builder.build())
  }

  private fun collection(): MongoCollection<Document> =
    clientHandle.value
      .getDatabase(ArchiveConfig.db)
      .getCollection(ArchiveConfig.collection)

  /**
   * 幂等写入: 以 date 为 _id 整文档替换 (upsert)。
   * 因往日 key 不再被写入, 重复归档同一天总是用相同数据覆盖, 不会重复计数; DEL 失败后的重试也安全。
   */
  suspend fun upsert(summary: DauDailySummary) {
    ensureEnabled()
    val command = Document().apply { summary.command.forEach { (name, count) -> append(name, count) } }
    val doc = Document("_id", summary.date)
      .append("message", summary.message)
      .append("userCount", summary.userCount)
      .append("contactCount", summary.contactCount)
      .append("command", command)
      .append("archivedAt", Date(now().toEpochMilliseconds()))
      .append("schemaVersion", ArchiveSchemaVersion)
    collection().replaceOne(Filters.eq("_id", summary.date), doc, ReplaceOptions().upsert(true))
  }

  /** 读取已归档的某天摘要; 不存在返回 null。字段缺失或类型异常视为故障抛出。 */
  suspend fun find(date: String): DauDailySummary? {
    ensureEnabled()
    val doc = collection().find(Filters.eq("_id", date)).firstOrNull() ?: return null
    val commandDoc = doc.get("command", Document::class.java) ?: Document()
    val command = commandDoc.entries.associate { (name, value) ->
      name to ((value as? Number)?.toLong()
        ?: error("archived command count not numeric: date=$date, command=$name"))
    }
    return DauDailySummary(
      date = date,
      message = doc.requiredLong("message"),
      userCount = doc.requiredLong("userCount"),
      contactCount = doc.requiredLong("contactCount"),
      command = command,
    )
  }

  /** 释放连接池; 仅在确已建连时关闭。 */
  fun close() {
    if (clientHandle.isInitialized()) runCatching { clientHandle.value.close() }
  }

  private fun ensureEnabled() {
    if (!ArchiveConfig.enabled) throw ArchiveUnavailableException("DAU 归档未启用")
  }

  private fun Document.requiredLong(field: String): Long =
    (this[field] as? Number)?.toLong() ?: error("archived field missing or not numeric: $field")
}
