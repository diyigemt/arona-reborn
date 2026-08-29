package com.diyigemt.arona.chatbot

import com.diyigemt.arona.database.DatabaseProvider
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.bson.Document
import java.util.Date

/**
 * 图片状态机: analyzing (已抢占, 分析中) → captioned (打过标但不入库) / pending (等人工审核) / ready (可用).
 * rejected (不是表情或 nsfw, 抓取路径已不再产出, 旧行与运营操作仍在) / hidden (曾可用, 管理员下架).
 * 四个终态之间由运营页 ([ChatbotEndpoint]) 自由切换; 选图只取 ready.
 * captioned 是看图打标留下的仅摘要记录 (非表情 / 超尺寸 / nsfw 高 / 该群未开收集), 无文件, 不进运营页,
 * 永久占住 hash 供 [ChatStore.setImageSummary] 回填复用; 之后群再开收集这些图也不会自动进库 (与墓碑同款语义).
 * deleted 是运营页删除留下的墓碑: 文件已删, 记录永留 (占住 hash 让 [StickerStore.claim] 跳过, 同图不再送模型/入库),
 * 不进列表也不可再编辑, 只能经删除端点到达.
 */
internal object StickerStatus {
  const val ANALYZING = "analyzing"
  const val CAPTIONED = "captioned"
  const val PENDING = "pending"
  const val READY = "ready"
  const val REJECTED = "rejected"
  const val HIDDEN = "hidden"
  const val DELETED = "deleted"
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
  val groupIds: List<String>,
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
  /** 无文件的 (旧版抓取时被拒的 rejected 行) 永远不能设为 ready; 运营页据此隐藏「通过」. */
  val hasFile: Boolean,
)

/**
 * 图库 `chatSticker`, `_id` = 内容 SHA-256 (全局去重). 一张图在多个群出现时 `groupIds` 累加, 选图默认只选本群见过的.
 * 字段: groupIds, senderId (首见), mime, width, height, bytes, summary, tags, nsfwRisk, status, fileName (数据目录里的文件, captioned/rejected 无), createdAt, useCount, lastUsedAt.
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

  /** 按内容 hash 取已有摘要, 供重复图零成本回填; analyzing / 被 release 的行没有 summary, 自然返回 null. */
  suspend fun findSummary(hash: String): String? =
    stickers().find(Filters.eq("_id", hash))
      .projection(Projections.include("summary"))
      .limit(1)
      .toList()
      .firstOrNull()
      ?.getString("summary")?.trim()?.takeIf { it.isNotEmpty() }

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
    // 只写 analyzing 行, 终态不会被打回. 过期接管与原协程迟到的 finish 理论上可交错 (无 owner token),
    // 但 _id 即内容 hash, 双方写的是同一张图的分析结果, 实害为零, 不值得为此加租约.
    stickers().updateOne(Filters.and(Filters.eq("_id", hash), Filters.eq("status", StickerStatus.ANALYZING)), update)
  }

  /** 分析失败时撤销抢占, 让下次再见到这张图时重试. 只删仍是 analyzing 的行. */
  suspend fun release(hash: String) {
    stickers().deleteOne(Filters.and(Filters.eq("_id", hash), Filters.eq("status", StickerStatus.ANALYZING)))
  }

  /** 可用候选: ready 且非 high; 默认限定本群见过的, [shared] 时全库. */
  suspend fun candidates(groupId: String, shared: Boolean): List<StickerCandidate> {
    val base = Filters.and(Filters.eq("status", StickerStatus.READY), Filters.ne("nsfwRisk", "high"), Filters.ne("fileName", null))
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

  // ---- 运营页 (P3). 只有超管 (主配置 superAdminUid) 能用, 全库可见, 按来源群过滤只是方便审核, 见 ChatbotEndpoint ----

  /**
   * 不在分析中的, 新到旧; [groupId] 非空时只看该群见过的.
   * ponytail: 上限 1000 不分页, 且现有索引不服务这条查询 (全扫 + 内存排序); 图库远到不了这个量, 到了再加游标和 createdAt 索引.
   */
  suspend fun list(groupId: String?, urlOf: (String) -> String?): List<StickerView> =
    stickers().find(Filters.and(listOfNotNull(operable(), groupId?.let { Filters.eq("groupIds", it) })))
      .sort(Sorts.descending("createdAt"))
      .limit(LIST_LIMIT)
      .toList()
      .map { doc ->
        StickerView(
          id = doc.getString("_id"),
          groupIds = doc.getList("groupIds", String::class.java) ?: emptyList(),
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
          hasFile = doc.getString("fileName") != null,
        )
      }

  /** 所有来源群 (不含仅 analyzing / deleted 行的), 给运营页做过滤下拉. */
  suspend fun sourceGroupIds(): List<String> =
    stickers().distinct("groupIds", operable(), String::class.java).toList()

  /** 运营页可见范围: 排除分析中、仅摘要 (captioned) 与墓碑. */
  private fun operable() = Filters.nin("status", StickerStatus.ANALYZING, StickerStatus.CAPTIONED, StickerStatus.DELETED)

  /** 只改 [edit] 里非 null 的字段. 返回 false = 不存在 / 仍在分析 / 仅摘要 (captioned) / 想设 ready 但没有文件 (旧版被拒行). */
  suspend fun update(id: String, edit: StickerEdit): Boolean {
    val sets = listOfNotNull(
      edit.status?.let { Updates.set("status", it) },
      edit.tags?.let { Updates.set("tags", it) },
      edit.summary?.let { Updates.set("summary", it) },
    )
    // rejected 行的 fileName 是显式 null, exists() 对 null 也为真, 要用 ne(null).
    val filter = if (edit.status == StickerStatus.READY) Filters.and(editable(id), Filters.ne("fileName", null)) else editable(id)
    return stickers().updateOne(filter, Updates.combine(sets)).matchedCount == 1L
  }

  /**
   * 软删除: 状态置 deleted, 返回改前文档让调用方删文件; null = 不存在 / 仍在分析 / 已删过.
   * 记录永留占住 hash, 同图再见时 [claim] 走 DuplicateKey 跳过, 不再消耗视觉额度也不会重新入库.
   * fileName 留在墓碑里: 没有读路径会用它 (选图只看 ready, 墓碑不进列表), 删文件失败时它是孤儿文件的唯一线索.
   */
  suspend fun delete(id: String): Document? =
    stickers().findOneAndUpdate(editable(id), Updates.set("status", StickerStatus.DELETED))

  /** 墓碑 (deleted)、仅摘要 (captioned) 与分析中的都不可运营: 不列出、不可编辑、不可删. */
  private fun editable(id: String) =
    Filters.and(Filters.eq("_id", id), Filters.nin("status", StickerStatus.ANALYZING, StickerStatus.CAPTIONED, StickerStatus.DELETED))

  private fun Document.int(key: String) = (get(key) as? Number)?.toInt()

  private const val LIST_LIMIT = 1_000
  private const val DUPLICATE_KEY = 11000
}
