package com.diyigemt.arona.rollpig.service

import com.diyigemt.arona.rollpig.PluginMain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * pighub.top 图片列表缓存, 供「随机小猪」取一张原图 URL。
 *
 * `GET /api/all-images` 返回全量列表(约 1200+ 张), 故按 [TTL] 做内存快照、避免每次指令都拉全量;
 * 取数失败时沿用旧快照, 不让 pighub 短暂抖动拖垮指令。HttpClient 与 magic/kivotos 等现有插件一致,
 * 使用长生命周期单例(插件当前无统一 unload/close 钩子), 并设请求超时以免 pighub 慢响应时调用堆积。
 */
internal object PigHubGallery {
  private const val ORIGIN = "https://pighub.top"
  private const val API = "$ORIGIN/api/all-images"
  private val TTL = 1.hours
  private val RETRY_BACKOFF = 1.minutes // 拉取失败/空后的短退避, 避免 pighub 不可用时每次指令都联网阻塞

  private val client = HttpClient(CIO) {
    engine {
      requestTimeout = 10_000 // ms; 兜底 pighub 慢响应/不可用, 避免 mutex 后调用排队累积
    }
  }
  private val json = Json { ignoreUnknownKeys = true }
  private val mutex = Mutex()

  @Volatile
  private var cache: List<String> = emptyList()

  // 下一次允许联网刷新的最早时刻(ms): 成功后推到 +TTL, 失败/空后只推 +RETRY_BACKOFF。
  @Volatile
  private var nextAttemptAt: Long = 0L

  @Serializable
  private data class AllImages(val images: List<PigHubImage> = emptyList())

  @Serializable
  private data class PigHubImage(val thumbnail: String = "")

  /** 随机取一张 pighub 原图的完整 URL; 列表始终为空(从未成功拉取)时返回 null。 */
  suspend fun randomImageUrl(): String? {
    ensureFresh()
    val snapshot = cache
    if (snapshot.isEmpty()) return null
    return snapshot[ThreadLocalRandom.current().nextInt(snapshot.size)]
  }

  private suspend fun ensureFresh() {
    if (System.currentTimeMillis() < nextAttemptAt) return
    mutex.withLock {
      // 双检: 等锁期间可能已被其它协程刷新, 用退避时刻判定即可(无须再看 cache)。
      val now = System.currentTimeMillis()
      if (now < nextAttemptAt) return
      val fresh = runCatching { fetch() }
        .onFailure { PluginMain.logger.warn("拉取 pighub 图片列表失败, 沿用旧快照(${cache.size} 张)", it) }
        .getOrNull()
      if (fresh.isNullOrEmpty()) {
        if (fresh != null) PluginMain.logger.warn("pighub 图片列表为空, 沿用旧快照(${cache.size} 张)")
        // 失败或空: 仅短退避, 既不长时间不再尝试, 也不让每次指令都联网。
        nextAttemptAt = now + RETRY_BACKOFF.inWholeMilliseconds
        return
      }
      cache = fresh
      nextAttemptAt = now + TTL.inWholeMilliseconds
      PluginMain.logger.info("pighub 图片列表刷新完成, 共 ${fresh.size} 张")
    }
  }

  private suspend fun fetch(): List<String> {
    val text = client.get(API).bodyAsText()
    return json.decodeFromString<AllImages>(text).images
      .asSequence()
      .mapNotNull { it.thumbnail.trim().takeIf(String::isNotEmpty) }
      // thumbnail 形如 /data/<中文文件名>.jpg, 需对路径里的非 ASCII 编码(保留 '/'); 已是绝对 URL 则原样用。
      .map { if (it.startsWith("http://") || it.startsWith("https://")) it else ORIGIN + it.encodeURLPath() }
      .map(::markdownSafeUrl)
      .distinct()
      .toList()
  }

  /**
   * encodeURLPath 不会编码 `()`(它们是合法路径字符), 但 Markdown 链接目标里的 `)` 会提前闭合
   * `![..](url)` 导致解析失败。这里把 `()` 额外转义为 `%28`/`%29`(语义等价, 服务端按原字符处理)。
   */
  private fun markdownSafeUrl(url: String): String = url.replace("(", "%28").replace(")", "%29")
}
