package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.DatabaseProvider
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.toList
import org.bson.Document
import java.util.Date

/** 表情状态机: analyzing (已抢占, 分析中) → pending (等人工审核) / ready (可用) / rejected (不是表情或 nsfw). hidden 留给 P3 webui. */
internal object StickerStatus {
  const val ANALYZING = "analyzing"
  const val PENDING = "pending"
  const val READY = "ready"
  const val REJECTED = "rejected"
}

/** 选图用的最小投影. */
internal data class StickerCandidate(val id: String, val tags: List<String>, val summary: String, val cosKey: String)

/**
 * 图库 `chatSticker`, `_id` = 内容 SHA-256 (全局去重). 一张图在多个群出现时 `groupIds` 累加, 选图默认只选本群见过的.
 * 字段: groupIds, senderId (首见), mime, width, height, bytes, summary, tags, nsfwRisk, status, cosKey, createdAt, useCount, lastUsedAt.
 */
internal object StickerStore {
  private const val COLLECTION = "chatSticker"
  private const val CANDIDATE_LIMIT = 1_000
  private const val STALE_CLAIM_MILLIS = 5 * 60_000L

  private fun stickers() = DatabaseProvider.defaultMongoDatabase.getCollection<Document>(COLLECTION)

  suspend fun ensureIndexes() {
    stickers().createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("groupIds")))
  }

  /**
   * 原子抢占: 不存在则插入 analyzing 行; 存在且是过期的 analyzing (进程崩溃遗留) 则接管; 否则 DuplicateKey → 已有记录,
   * 只把本群加进 groupIds. 返回 true 表示由调用方负责分析并 [finish] / [release].
   */
  suspend fun claim(hash: String, groupId: String, senderId: String): Boolean {
    val now = Date()
    val staleClaim = Filters.and(
      Filters.eq("_id", hash),
      Filters.eq("status", StickerStatus.ANALYZING),
      Filters.lt("createdAt", Date(now.time - STALE_CLAIM_MILLIS)),
    )
    val update = Updates.combine(
      Updates.set("status", StickerStatus.ANALYZING),
      Updates.set("createdAt", now),
      Updates.set("senderId", senderId),
      Updates.addToSet("groupIds", groupId),
      Updates.setOnInsert("useCount", 0),
    )
    return try {
      stickers().updateOne(staleClaim, update, UpdateOptions().upsert(true))
      true
    } catch (e: MongoWriteException) {
      if (e.code != DUPLICATE_KEY) throw e
      stickers().updateOne(Filters.eq("_id", hash), Updates.addToSet("groupIds", groupId))
      false
    }
  }

  suspend fun finish(hash: String, image: DownloadedImage, dimensions: Pair<Int, Int>?, analysis: StickerAnalysis, status: String, cosKey: String?) {
    val update = Updates.combine(
      Updates.set("status", status),
      Updates.set("mime", image.mime),
      Updates.set("width", dimensions?.first),
      Updates.set("height", dimensions?.second),
      Updates.set("bytes", image.bytes.size),
      Updates.set("summary", analysis.summary),
      Updates.set("tags", analysis.tags),
      Updates.set("nsfwRisk", analysis.nsfwRisk),
      Updates.set("cosKey", cosKey),
    )
    // 只写仍由自己持有的 analyzing 行: 过期接管与原协程迟到的 finish 不会互相覆盖.
    stickers().updateOne(Filters.and(Filters.eq("_id", hash), Filters.eq("status", StickerStatus.ANALYZING)), update)
  }

  /** 分析失败时撤销抢占, 让下次再见到这张图时重试. 只删仍是 analyzing 的行. */
  suspend fun release(hash: String) {
    stickers().deleteOne(Filters.and(Filters.eq("_id", hash), Filters.eq("status", StickerStatus.ANALYZING)))
  }

  /** 可用候选: ready 且非 high; 默认限定本群见过的, [shared] 时全库. */
  suspend fun candidates(groupId: String, shared: Boolean): List<StickerCandidate> {
    val base = Filters.and(Filters.eq("status", StickerStatus.READY), Filters.ne("nsfwRisk", "high"), Filters.exists("cosKey"))
    val filter = if (shared) base else Filters.and(base, Filters.eq("groupIds", groupId))
    return stickers().find(filter)
      .projection(Projections.include("tags", "summary", "cosKey"))
      .limit(CANDIDATE_LIMIT)
      .toList()
      .mapNotNull { doc ->
        val key = doc.getString("cosKey") ?: return@mapNotNull null
        StickerCandidate(doc.getString("_id"), doc.getList("tags", String::class.java) ?: emptyList(), doc.getString("summary") ?: "", key)
      }
  }

  suspend fun markUsed(id: String) {
    stickers().updateOne(Filters.eq("_id", id), Updates.combine(Updates.inc("useCount", 1), Updates.set("lastUsedAt", Date())))
  }

  private const val DUPLICATE_KEY = 11000
}
