package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.DatabaseProvider
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.Date
import java.util.concurrent.TimeUnit

/** 一条群聊消息 (入站或 bot 自己发出的), 观察库与 prompt 装配的共同形态. */
data class ChatLine(
  /** 入站为平台 msg_id, 出站为发送回执 id; 作为 `_id` 做 upsert 幂等 (webhook 重投不写两条). */
  val id: String,
  val groupId: String,
  val senderId: String,
  val senderName: String?,
  val content: String,
  val fromBot: Boolean,
  val ts: Date,
)

/**
 * 观察库 `chatContext` 与异常 noop 库 `chatNoop`.
 *
 * 刻意用 [Document] + 驱动默认 codec 而非 kotlinx 数据类: TTL 索引只认 BSON Date, 而 bson-kotlinx 的
 * `InstantAsBsonDateTime` 绑定的是 kotlinx-datetime 0.7 已改为 typealias 的 `kotlinx.datetime.Instant`,
 * 不想把清理策略押在 codec 兼容性上. 同仓库先例: user-recorder 的 DauArchiveRepository.
 */
internal object ChatStore {
  private const val CONTEXT = "chatContext"
  private const val NOOP = "chatNoop"
  private const val NOOP_TTL_DAYS = 7L

  private fun context() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(CONTEXT)
  private fun noop() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(NOOP)

  /** 幂等建索引: (groupId, ts) 供 history 查询, ts 上挂 TTL. 已存在同名同选项的索引驱动会跳过. */
  suspend fun ensureIndexes(contextTtlHours: Long) {
    context().createIndex(Indexes.compoundIndex(Indexes.ascending("groupId"), Indexes.descending("ts")))
    context().createIndex(Indexes.ascending("ts"), IndexOptions().expireAfter(contextTtlHours, TimeUnit.HOURS))
    noop().createIndex(Indexes.ascending("ts"), IndexOptions().expireAfter(NOOP_TTL_DAYS, TimeUnit.DAYS))
  }

  suspend fun upsert(line: ChatLine) {
    val doc = Document("_id", line.id)
      .append("groupId", line.groupId)
      .append("senderId", line.senderId)
      .append("senderName", line.senderName)
      .append("content", line.content)
      .append("fromBot", line.fromBot)
      .append("ts", line.ts)
    context().replaceOne(Filters.eq("_id", line.id), doc, ReplaceOptions().upsert(true))
  }

  /**
   * 最近 [limit] 条, 按时间正序返回. 排除 [excludeId]: observe 与回复两个 listener 并发, 当前消息可能已落库,
   * 它会作为 currentText 单独进 prompt, 不能在 history 里再出现一次.
   */
  suspend fun history(groupId: String, excludeId: String, limit: Int): List<ChatLine> =
    context()
      .find(Filters.and(Filters.eq("groupId", groupId), Filters.ne("_id", excludeId)))
      .sort(Sorts.descending("ts"))
      .limit(limit)
      .toList()
      .asReversed()
      .map { it.toChatLine() }

  /** 只记异常类 noop (模型/审核/发送/预算), 常规类走 Redis 计数, 见 [NoopReason.persistToMongo]. */
  suspend fun recordNoop(groupId: String, messageId: String, reason: NoopReason, detail: String?) {
    noop().insertOne(
      Document("groupId", groupId)
        .append("messageId", messageId)
        .append("reason", reason.name)
        .append("detail", detail)
        .append("ts", Date()),
    )
  }

  private fun Document.toChatLine() = ChatLine(
    id = getString("_id"),
    groupId = getString("groupId"),
    senderId = getString("senderId") ?: "",
    senderName = getString("senderName"),
    content = getString("content") ?: "",
    fromBot = getBoolean("fromBot", false),
    ts = getDate("ts") ?: Date(0),
  )
}
