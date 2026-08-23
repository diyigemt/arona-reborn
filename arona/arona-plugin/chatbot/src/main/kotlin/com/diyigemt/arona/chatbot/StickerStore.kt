package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.DatabaseProvider
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.Document
import java.util.Date

/**
 * 表情状态机: analyzing (已抢占, 分析中) → pending (等人工审核) / ready (可用) / rejected (不是表情或 nsfw) / hidden (曾可用, 管理员下架).
 * 四个终态之间由运营页 ([ChatbotEndpoint]) 自由切换; 选图只取 ready.
 */
internal object StickerStatus {
  const val ANALYZING = "analyzing"
  const val PENDING = "pending"
  const val READY = "ready"
  const val REJECTED = "rejected"
  const val HIDDEN = "hidden"
  val TERMINAL = setOf(PENDING, READY, REJECTED, HIDDEN)
}

/** 选图用的最小投影. */
internal data class StickerCandidate(val id: String, val tags: List<String>, val summary: String, val fileName: String)

/** 运营页编辑, null = 该字段不改. 由 [normalizeStickerEdit] 产出, 已经过裁剪. */
internal data class StickerEdit(val status: String?, val tags: List<String>?, val summary: String?)

/** 运营页列表项. `url` = [ChatbotSecrets.stickerPublicBaseUrl] + 文件名 (前缀没配时 null). */
@Serializable
internal data class StickerView(
  val id: String,
  val status: String,
  val tags: List<String>,
  val summary: String,
  val nsfwRisk: String?,
  val width: Int?,
  val height: Int?,
  val bytes: Int?,
  val useCount: Int,
  val createdAt: Long?,
  val lastUsedAt: Long?,
  val senderId: String?,
  val url: String?,
)

/**
 * 图库 `chatSticker`, `_id` = 内容 SHA-256 (全局去重). 一张图在多个群出现时 `groupIds` 累加, 选图默认只选本群见过的.
 * 字段: groupIds, senderId (首见), mime, width, height, bytes, summary, tags, nsfwRisk, status, fileName (数据目录里的文件, rejected 无), createdAt, useCount, lastUsedAt.
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

  suspend fun finish(hash: String, image: DownloadedImage, dimensions: Pair<Int, Int>?, analysis: StickerAnalysis, status: String, fileName: String?) {
    val update = Updates.combine(
      Updates.set("status", status),
      Updates.set("mime", image.mime),
      Updates.set("width", dimensions?.first),
      Updates.set("height", dimensions?.second),
      Updates.set("bytes", image.bytes.size),
      Updates.set("summary", analysis.summary),
      Updates.set("tags", analysis.tags),
      Updates.set("nsfwRisk", analysis.nsfwRisk),
      Updates.set("fileName", fileName),
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
    val base = Filters.and(Filters.eq("status", StickerStatus.READY), Filters.ne("nsfwRisk", "high"), Filters.exists("fileName"))
    val filter = if (shared) base else Filters.and(base, Filters.eq("groupIds", groupId))
    return stickers().find(filter)
      .projection(Projections.include("tags", "summary", "fileName"))
      .limit(CANDIDATE_LIMIT)
      .toList()
      .mapNotNull { doc ->
        val fileName = doc.getString("fileName") ?: return@mapNotNull null
        StickerCandidate(doc.getString("_id"), doc.getList("tags", String::class.java) ?: emptyList(), doc.getString("summary") ?: "", fileName)
      }
  }

  suspend fun markUsed(id: String) {
    stickers().updateOne(Filters.eq("_id", id), Updates.combine(Updates.inc("useCount", 1), Updates.set("lastUsedAt", Date())))
  }

  // ---- 运营页 (P3). 管理员只能看/动本群见过的图 (groupIds 含 gid), 但 status/tags/summary 字段本身是全局的, 见 ChatbotEndpoint ----

  /**
   * 本群见过且不在分析中的, 新到旧.
   * ponytail: 上限 1000 不分页, 且 (status, groupIds) 索引不服务这条查询 (全扫 + 内存排序); 群级图库远到不了这个量, 到了再加游标和 (groupIds, createdAt) 索引.
   */
  suspend fun list(groupId: String, urlOf: (String) -> String?): List<StickerView> =
    stickers().find(Filters.and(Filters.eq("groupIds", groupId), Filters.ne("status", StickerStatus.ANALYZING)))
      .sort(Sorts.descending("createdAt"))
      .limit(LIST_LIMIT)
      .toList()
      .map { doc ->
        StickerView(
          id = doc.getString("_id"),
          status = doc.getString("status") ?: "",
          tags = doc.getList("tags", String::class.java) ?: emptyList(),
          summary = doc.getString("summary") ?: "",
          nsfwRisk = doc.getString("nsfwRisk"),
          width = doc.int("width"),
          height = doc.int("height"),
          bytes = doc.int("bytes"),
          useCount = doc.int("useCount") ?: 0,
          createdAt = doc.getDate("createdAt")?.time,
          lastUsedAt = doc.getDate("lastUsedAt")?.time,
          senderId = doc.getString("senderId"),
          url = doc.getString("fileName")?.let(urlOf),
        )
      }

  /** 只改 [edit] 里非 null 的字段. 返回 false = 不存在 / 不属于本群 / 仍在分析. */
  suspend fun update(id: String, groupId: String, edit: StickerEdit): Boolean {
    val sets = listOfNotNull(
      edit.status?.let { Updates.set("status", it) },
      edit.tags?.let { Updates.set("tags", it) },
      edit.summary?.let { Updates.set("summary", it) },
    )
    return stickers().updateOne(owned(id, groupId), Updates.combine(sets)).matchedCount == 1L
  }

  /**
   * 从本群移除: 只 `$pull` groupIds 里的本群; 没有别的群见过它时才物理删行, 并返回文件名让调用方删文件.
   * A 群管理员删不掉 B 群还在用的图 (B 再发同图时走 claim 的 E11000 分支, 本群会被重新加回来).
   * 返回 null = 不存在 / 不属于本群 / 仍在分析.
   */
  suspend fun unlink(id: String, groupId: String): Unlinked? {
    val after = stickers().findOneAndUpdate(
      owned(id, groupId),
      Updates.pull("groupIds", groupId),
      FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
    ) ?: return null
    if (!after.getList("groupIds", String::class.java).isNullOrEmpty()) return Unlinked(fileToDelete = null)
    // 只删仍然没人要的行: pull 与 delete 之间别的群可能刚 addToSet 回来, 那就留着 (文件也留着).
    val deleted = stickers().deleteOne(Filters.and(Filters.eq("_id", id), Filters.size("groupIds", 0))).deletedCount == 1L
    return Unlinked(fileToDelete = after.getString("fileName").takeIf { deleted })
  }

  class Unlinked(val fileToDelete: String?)

  private fun owned(id: String, groupId: String) =
    Filters.and(Filters.eq("_id", id), Filters.eq("groupIds", groupId), Filters.ne("status", StickerStatus.ANALYZING))

  private fun Document.int(key: String) = (get(key) as? Number)?.toInt()

  private const val LIST_LIMIT = 1_000
  private const val DUPLICATE_KEY = 11000
}
