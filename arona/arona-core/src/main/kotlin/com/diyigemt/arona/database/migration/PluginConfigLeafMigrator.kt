package com.diyigemt.arona.database.migration

import com.diyigemt.arona.database.toBsonDocument
import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.jsonObject
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.BsonValue

/**
 * 把 Plugin Webui 配置叶子从 BSON 字符串就地升级为 BSON 子文档.
 *
 * 历史 schema: `config: Map<String, Map<String, String>>` — 叶子是 `Json.encodeToString(value)` 的产物;
 * 新 schema: `config: Map<String, Map<String, JsonObject>>` — 叶子是原生 BSON Document.
 * 批 2 切完类型后, kotlinx codec 拒解旧 BsonString 叶子, bot 起不来; 必须先跑本迁移再开放服务.
 *
 * 设计:
 * - 完成点用 [MIGRATION_LOG_COLLECTION] / `_id = plugin_config_leaf_v2` 标记, 避免无限期 lazy 兼容
 * - 备份 `User__pre_v2` / `Contact__pre_v2` (aggregate `$out`, 已存在则跳过, 不覆盖前次失败留下的备份)
 * - 扫库以原始 BSON 读 (不走 Mongo wrapper codec, 否则 BsonString 叶子在 decode 时已抛)
 * - 解析失败 (leaf 非 JSON 对象 / JSON 非法) → throw, halt 启动. 启动钩子捕获后失败.
 *
 * 失败语义: throw 让 `runBlocking { runOnceIfNeeded(...) }` 把异常抛回 main, JVM 退出.
 * 半途失败时 MigrationLog 留下 `started_at` 但无 `completed_at`, 下次启动重试.
 */
internal object PluginConfigLeafMigrator {
  private const val MIGRATION_ID = "plugin_config_leaf_v2"
  private const val MIGRATION_LOG_COLLECTION = "MigrationLog"
  private const val USER_COLLECTION = "User"
  private const val CONTACT_COLLECTION = "Contact"
  private const val USER_BACKUP_COLLECTION = "User__pre_v2"
  private const val CONTACT_BACKUP_COLLECTION = "Contact__pre_v2"

  suspend fun runOnceIfNeeded(database: MongoDatabase): MigrationOutcome {
    val log = database.getCollection<BsonDocument>(MIGRATION_LOG_COLLECTION)
    val existing = log.find(Filters.eq("_id", MIGRATION_ID)).firstOrNull()
    // 只有 `completed_at` 存在且非 null 才算完成. 仅 containsKey 不够: 半途失败时我们用 Updates.unset
    // 已经移除该字段, 但若运维手工置成 BsonNull 也得视为"未完成", 否则会跳过迁移留下半成品.
    val completedAt = existing?.get("completed_at")
    if (completedAt != null && !completedAt.isNull) {
      commandLineLogger.info("Mongo migration $MIGRATION_ID already completed at $completedAt, skipping")
      return MigrationOutcome.AlreadyDone
    }

    commandLineLogger.info("Starting Mongo migration $MIGRATION_ID")
    log.updateOne(
      Filters.eq("_id", MIGRATION_ID),
      Updates.combine(
        Updates.set("started_at", currentDateTime()),
        Updates.unset("completed_at"),
      ),
      UpdateOptions().upsert(true),
    )

    ensureBackup(database, USER_COLLECTION, USER_BACKUP_COLLECTION)
    ensureBackup(database, CONTACT_COLLECTION, CONTACT_BACKUP_COLLECTION)

    val userStats = migrateCollection(database.getCollection(USER_COLLECTION), includeMembers = false)
    val contactStats = migrateCollection(database.getCollection(CONTACT_COLLECTION), includeMembers = true)

    log.updateOne(
      Filters.eq("_id", MIGRATION_ID),
      Updates.combine(
        Updates.set("completed_at", currentDateTime()),
        Updates.set("user_stats", userStats.toBsonDocument()),
        Updates.set("contact_stats", contactStats.toBsonDocument()),
      ),
    )
    commandLineLogger.info(
      "Completed Mongo migration $MIGRATION_ID: user=$userStats contact=$contactStats",
    )
    return MigrationOutcome.Completed(userStats = userStats, contactStats = contactStats)
  }

  /**
   * 用 aggregate `$out` 一次性复制源集合到备份集合. `$out` 会替换整个目标, 因此前置检查目标是否存在,
   * 已存在则跳过 — 前次失败留下的备份不能被覆盖, 避免回滚源丢失.
   */
  private suspend fun ensureBackup(database: MongoDatabase, source: String, backup: String) {
    val existing = database.listCollectionNames().toList().toSet()
    if (backup in existing) {
      commandLineLogger.info("Mongo migration backup $backup already exists, not overwriting")
      return
    }
    commandLineLogger.info("Creating Mongo migration backup $backup from $source")
    database.getCollection<BsonDocument>(source)
      .aggregate<BsonDocument>(listOf(Aggregates.out(backup)))
      .toList()
  }

  private suspend fun migrateCollection(
    collection: MongoCollection<BsonDocument>,
    includeMembers: Boolean,
  ): MigrationStats {
    val stats = MigrationStats()
    // 小集群规模, 一次性 toList 简化逻辑; 大库需改流式 collect.
    collection.find().toList().forEach { doc ->
      val migrated = migrateDocument(doc, includeMembers = includeMembers, stats = stats)
      if (migrated != null) {
        collection.replaceOne(
          Filters.eq("_id", doc["_id"]),
          migrated,
          ReplaceOptions().upsert(false),
        )
      }
    }
    return stats
  }
}

internal sealed class MigrationOutcome {
  data object AlreadyDone : MigrationOutcome()
  data class Completed(
    val userStats: MigrationStats,
    val contactStats: MigrationStats,
  ) : MigrationOutcome()
}

internal data class MigrationStats(
  var scanned: Int = 0,
  var converted: Int = 0,
  var failedLeaves: Int = 0,
  var unexpectedLeafTypes: Int = 0,
) {
  fun toBsonDocument(): BsonDocument = BsonDocument()
    .append("scanned", BsonInt32(scanned))
    .append("converted", BsonInt32(converted))
    .append("failed_leaves", BsonInt32(failedLeaves))
    .append("unexpected_leaf_types", BsonInt32(unexpectedLeafTypes))
}

/**
 * 纯函数 transform: 返回 null 表示文档无需写回 (无 config 字段, 或所有叶子已是 BsonDocument).
 *
 * - leaf 是 BsonString: 视为遗留 JSON 文本, 按标准 JSON 解析为 JsonObject, 跑 leaf-key 安全校验,
 *   再用 [com.diyigemt.arona.database.toBsonDocument] (与读路径同款映射) 转 BsonDocument.
 *   不能用 `BsonDocument.parse` — 它是 Mongo Extended JSON parser, 会把内部首字段名命中
 *   `$date`/`$oid`/`$numberLong`/`$numberDecimal`/`$timestamp`/`$symbol`/`$binary` 等的子对象
 *   误解析成 BsonDateTime / Decimal128 等非 JSON 兼容 BSON 类型, 之后 [com.diyigemt.arona.database.KotlinxJsonElementCodecProvider]
 *   读路径拒解, 服务起不来.
 * - leaf 已是 BsonDocument: 保留, 幂等
 * - leaf 是其它类型 (BsonNull / BsonInt32 等): 保留 + stats.unexpectedLeafTypes++ (异常但容忍)
 * - includeMembers=true: 额外递归 `members[]` 数组里的每个 member doc (内部用 includeMembers=false)
 *
 * `stats.scanned` 只在最外层加 1 (members 内部递归不再叠加 scanned, 否则单 Contact 文档会被多次计数).
 *
 * 失败语义: 解析非标准 JSON / leaf-key 命中 `.` 或 `$` 前缀 / kotlinx→BSON 写入失败, 一律
 * `failedLeaves++` 后 throw [IllegalStateException], 由 runOnceIfNeeded 把异常抛回 main, JVM 退出.
 * 旧数据如含 unsafe leaf-key (新写路径已禁), 必须人工清洗后才能继续迁移 — 否则迁出来的形态
 * 新写路径会拒, 用户陷入"读得到、改不了"死锁.
 */
internal fun migrateDocument(
  doc: BsonDocument,
  includeMembers: Boolean,
  stats: MigrationStats,
): BsonDocument? = migrateDocumentInternal(
  doc = doc,
  includeMembers = includeMembers,
  stats = stats,
  countScanned = true,
  rootPath = "doc(${renderId(doc)})",
)

private fun migrateDocumentInternal(
  doc: BsonDocument,
  includeMembers: Boolean,
  stats: MigrationStats,
  countScanned: Boolean,
  rootPath: String,
): BsonDocument? {
  if (countScanned) stats.scanned++
  val migrated = doc.clone()
  var dirty = false

  val config = migrated["config"]
  if (config?.isDocument == true) {
    dirty = migrateConfigBranch(config.asDocument(), stats, "$rootPath.config") || dirty
  }

  if (includeMembers) {
    val members = migrated["members"]
    if (members?.isArray == true) {
      dirty = migrateMembers(members.asArray(), stats, "$rootPath.members") || dirty
    }
  }

  return if (dirty) migrated else null
}

private fun migrateMembers(members: BsonArray, stats: MigrationStats, path: String): Boolean {
  var dirty = false
  members.values.forEachIndexed { index, member ->
    if (member.isDocument) {
      val migrated = migrateDocumentInternal(
        doc = member.asDocument(),
        includeMembers = false,
        stats = stats,
        countScanned = false,
        rootPath = "$path[$index](${renderId(member.asDocument())})",
      )
      if (migrated != null) {
        members[index] = migrated
        dirty = true
      }
    }
  }
  return dirty
}

private fun migrateConfigBranch(config: BsonDocument, stats: MigrationStats, path: String): Boolean {
  var dirty = false
  config.keys.toList().forEach { namespace ->
    val namespaceValue = config[namespace]
    if (namespaceValue?.isDocument != true) {
      stats.unexpectedLeafTypes++
      return@forEach
    }
    val namespaceDoc = namespaceValue.asDocument()
    namespaceDoc.keys.toList().forEach { key ->
      val leafPath = "$path.$namespace.$key"
      when (val leaf = namespaceDoc[key]) {
        null -> Unit
        is BsonString -> {
          namespaceDoc[key] = parseLegacyLeaf(leaf.value, leafPath, stats)
          stats.converted++
          dirty = true
        }
        else -> if (leaf.isDocument) Unit else stats.unexpectedLeafTypes++
      }
    }
  }
  return dirty
}

private fun parseLegacyLeaf(raw: String, path: String, stats: MigrationStats): BsonDocument {
  // catch Exception 而不是 Throwable: OOM / CancellationException / StackOverflowError 必须向上抛,
  // 不能被当成"数据损坏"包装. kotlinx JSON 解析 / leaf-key 校验 / kotlinx codec 写出都抛普通 Exception 子类.
  return try {
    val element = JsonIgnoreUnknownKeys.parseToJsonElement(raw).jsonObject
    PluginWebuiConfigRecorder.requireSafeBsonLeafKeys(element)
    element.toBsonDocument()
  } catch (e: Exception) {
    stats.failedLeaves++
    throw IllegalStateException(
      "Failed to migrate plugin config leaf at $path: ${e.message}; raw='${raw.take(200)}'",
      e,
    )
  }
}

private fun renderId(doc: BsonDocument): String =
  "_id=${doc["_id"]?.toDebugString() ?: "<missing>"}"

private fun BsonValue.toDebugString(): String = when {
  isString -> asString().value
  else -> toString()
}
