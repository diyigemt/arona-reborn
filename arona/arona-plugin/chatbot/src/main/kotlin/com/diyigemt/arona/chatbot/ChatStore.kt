package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.DatabaseProvider
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
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
  /** 表情抓取路径回写的图片描述 (P2), 独立字段: observe 重投的 $set 不会碰它. */
  val imageSummary: String? = null,
) {
  /** 进 prompt 的文本: 有图片描述时拼在正文后. */
  val promptText: String get() = imageSummary?.takeIf { it.isNotBlank() }?.let { "$content (图片内容: $it)" } ?: content
}

/** 每群一条滚动摘要: [coveredUntil] 是水位线, `ts > coveredUntil` 的行才进 history. */
data class ChatMemory(val groupId: String, val summary: String, val coveredUntil: Date)

/**
 * 观察库 `chatContext`, 异常 noop 库 `chatNoop`, 记忆库 `chatMemory`.
 *
 * 刻意用 [Document] + 驱动默认 codec 而非 kotlinx 数据类: TTL 索引只认 BSON Date, 而 bson-kotlinx 的
 * `InstantAsBsonDateTime` 绑定的是 kotlinx-datetime 0.7 已改为 typealias 的 `kotlinx.datetime.Instant`,
 * 不想把清理策略押在 codec 兼容性上. 同仓库先例: user-recorder 的 DauArchiveRepository.
 */
internal object ChatStore {
  private const val CONTEXT = "chatContext"
  private const val NOOP = "chatNoop"
  private const val MEMORY = "chatMemory"
  private const val ROUND = "chatRound"
  private const val NOOP_TTL_DAYS = 7L

  private fun context() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(CONTEXT)
  private fun noop() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(NOOP)
  private fun memory() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(MEMORY)
  private fun round() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(ROUND)

  /** 幂等建索引: (groupId, ts) 供 history 查询, ts 上挂 TTL; 摘要按 updatedAt 闲置过期. 已存在同名同选项的索引驱动会跳过. */
  suspend fun ensureIndexes(contextTtlHours: Long, memoryTtlDays: Long, roundLogTtlDays: Long) {
    context().createIndex(Indexes.compoundIndex(Indexes.ascending("groupId"), Indexes.descending("ts")))
    context().createIndex(Indexes.ascending("ts"), IndexOptions().expireAfter(contextTtlHours, TimeUnit.HOURS))
    noop().createIndex(Indexes.ascending("ts"), IndexOptions().expireAfter(NOOP_TTL_DAYS, TimeUnit.DAYS))
    memory().createIndex(Indexes.ascending("updatedAt"), IndexOptions().expireAfter(memoryTtlDays, TimeUnit.DAYS))
    round().createIndex(Indexes.ascending("ts"), IndexOptions().expireAfter(roundLogTtlDays, TimeUnit.DAYS))
  }

  /** `$set` 而非整行 replace: webhook 重投时不抹掉异步回写的 imageSummary. */
  suspend fun upsert(line: ChatLine) {
    val update = Updates.combine(
      Updates.set("groupId", line.groupId),
      Updates.set("senderId", line.senderId),
      Updates.set("senderName", line.senderName),
      Updates.set("content", line.content),
      Updates.set("fromBot", line.fromBot),
      Updates.set("ts", line.ts),
    )
    context().updateOne(Filters.eq("_id", line.id), update, UpdateOptions().upsert(true))
  }

  /** 一条消息多张图时首个完成的描述生效 (不拼接, 一句描述够用). */
  suspend fun setImageSummary(id: String, summary: String) {
    context().updateOne(Filters.and(Filters.eq("_id", id), Filters.exists("imageSummary", false)), Updates.set("imageSummary", summary))
  }

  /**
   * 最近 [limit] 条, 按时间正序返回. 排除 [excludeId]: observe 与回复两个 listener 并发, 当前消息可能已落库,
   * 它会作为 currentText 单独进 prompt, 不能在 history 里再出现一次. [since] 为摘要水位线, 已压缩的行不再出现.
   */
  suspend fun history(groupId: String, excludeId: String, limit: Int, since: Date? = null): List<ChatLine> =
    context()
      .find(Filters.and(uncoveredFilter(groupId, since), Filters.ne("_id", excludeId)))
      .sort(Sorts.descending("ts"))
      .limit(limit)
      .toList()
      .asReversed()
      .map { it.toChatLine() }

  suspend fun uncoveredCount(groupId: String, since: Date?): Long = context().countDocuments(uncoveredFilter(groupId, since))

  /** 水位线之后最旧的 [limit] 行, 按时间正序, 供压缩规划. */
  suspend fun uncovered(groupId: String, since: Date?, limit: Int): List<ChatLine> =
    context().find(uncoveredFilter(groupId, since)).sort(Sorts.ascending("ts")).limit(limit).toList().map { it.toChatLine() }

  private fun uncoveredFilter(groupId: String, since: Date?) =
    if (since == null) Filters.eq("groupId", groupId) else Filters.and(Filters.eq("groupId", groupId), Filters.gt("ts", since))

  suspend fun memory(groupId: String): ChatMemory? =
    memory().find(Filters.eq("_id", groupId)).limit(1).toList().firstOrNull()?.let {
      ChatMemory(groupId, it.getString("summary") ?: "", it.getDate("coveredUntil") ?: Date(0))
    }

  /** 单次 replaceOne 即原子提交: 水位线与摘要一起落, 中途崩溃下次重做 (输入不变, 幂等). */
  suspend fun saveMemory(memory: ChatMemory) {
    val doc = Document("_id", memory.groupId)
      .append("summary", memory.summary)
      .append("coveredUntil", memory.coveredUntil)
      .append("updatedAt", Date())
    memory().replaceOne(Filters.eq("_id", memory.groupId), doc, ReplaceOptions().upsert(true))
  }

  /** 每轮成功对话记一条: 装配好的完整 user prompt 与实际发出的回复 (system prompt 是群配置, 不重复落). */
  suspend fun recordRound(groupId: String, messageId: String, prompt: String, reply: String) {
    round().insertOne(
      Document("groupId", groupId)
        .append("messageId", messageId)
        .append("prompt", prompt)
        .append("reply", reply)
        .append("ts", Date()),
    )
  }

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
    imageSummary = getString("imageSummary"),
  )
}
