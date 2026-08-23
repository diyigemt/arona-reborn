package com.diyigemt.arona.chatbot

import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 观察路径的表情抓取: 下载 → 尺寸启发式 → 抢占 hash → 视觉模型打标 → COS 持久化 → 落库.
 * 与观察落库解耦 (独立协程), 进程级并发 2 + 全局小时预算, 白名单群刷图也耗不光视觉额度.
 * ponytail: 回复路径看图会再下载一次同一张图, 回复只占图片消息的一小部分, 不做 URL 缓存.
 */
internal object StickerCapture {
  private const val MAX_IMAGES_PER_MESSAGE = 2
  private val semaphore = Semaphore(2)
  private val hourly by lazy {
    val n = ChatbotSecrets.stickerCapturePerHour.coerceAtLeast(1)
    IpRateLimiter(capacity = n, refillTokens = n, refillSeconds = 3600)
  }

  fun enabled(cfg: ChatbotConfig): Boolean =
    ChatbotSecrets.stickerCaptureEnabled && cfg.stickerCapture && StickerCos.isConfigured()

  suspend fun capture(client: HttpClient, lineId: String, groupId: String, senderId: String, images: List<TencentImage>) {
    images.take(MAX_IMAGES_PER_MESSAGE).forEach { image ->
      semaphore.withPermit {
        runCatchingCancellable { captureOne(client, lineId, groupId, senderId, image.url) }
          .onFailure { PluginMain.logger.warn("chatbot 表情抓取失败: ${image.url.take(120)}", it) }
      }
    }
  }

  private suspend fun captureOne(client: HttpClient, lineId: String, groupId: String, senderId: String, url: String) {
    val image = downloadImage(client, url, ChatbotSecrets.stickerMaxBytes, ChatbotSecrets.imageDownloadTimeoutMillis) ?: return
    val dimensions = probeDimensions(image.bytes)
    if (!looksLikeSticker(dimensions, ChatbotSecrets.stickerMaxSide, ChatbotSecrets.stickerMaxAspect)) return
    if (!StickerStore.claim(image.sha256, groupId, senderId)) return
    // 抢占之后才扣预算: 重复图不消耗视觉额度. 预算不足就撤销抢占, 下次再见重试.
    if (!hourly.tryConsume("global")) {
      StickerStore.release(image.sha256)
      return
    }
    val analysis = try {
      DeepSeekClient.analyzeSticker(image)?.normalized()
    } catch (t: Throwable) {
      StickerStore.release(image.sha256)
      throw t
    } ?: return StickerStore.release(image.sha256)

    analysis.summary.trim().takeIf { it.isNotEmpty() }?.let { summary ->
      runCatchingCancellable { ChatStore.setImageSummary(lineId, summary) }
        .onFailure { PluginMain.logger.warn("回写图片描述失败", it) }
    }
    val status = when {
      !analysis.isMeme || analysis.nsfwRisk == "high" -> StickerStatus.REJECTED
      ChatbotSecrets.stickerAutoApprove && analysis.nsfwRisk == "low" -> StickerStatus.READY
      else -> StickerStatus.PENDING
    }
    val cosKey = if (status == StickerStatus.REJECTED) null else StickerCos.keyFor(image).also {
      try {
        StickerCos.put(it, image)
      } catch (t: Throwable) {
        StickerStore.release(image.sha256)
        throw t
      }
    }
    StickerStore.finish(image.sha256, image, dimensions, analysis, status, cosKey)
    PluginMain.logger.info("chatbot 表情入库 $status: ${image.sha256.take(12)} ${analysis.tags.joinToString(" ")}")
  }

  private const val MAX_TAGS = 8
  private const val MAX_SUMMARY_CHARS = 200

  /** 模型输出不可信: 标签去空去重封顶, 摘要截断, 风险值只认三档 (其它按 mid 进 pending 等人审). */
  private fun StickerAnalysis.normalized() = copy(
    summary = summary.trim().take(MAX_SUMMARY_CHARS),
    tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS),
    nsfwRisk = nsfwRisk.trim().lowercase().takeIf { it in setOf("low", "mid", "high") } ?: "mid",
  )
}
