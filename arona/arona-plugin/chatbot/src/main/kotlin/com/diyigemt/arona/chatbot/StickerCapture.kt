package com.diyigemt.arona.chatbot

import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.utils.IpRateLimiter
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 抓取后的归宿 (纯函数): 够格入库的进审核流 (pending / autoApprove 时 ready);
 * 其余 (该群未开收集 / 非表情 / nsfw 高 / 超尺寸 / 超字节) 一律 captioned —— 只留摘要占 hash, 不存文件.
 * 抓取路径从此不产出 rejected, 免得截图/照片灌满运营页; rejected 只剩运营操作.
 */
internal fun captureStatus(
  stickerEnabled: Boolean,
  autoApprove: Boolean,
  analysis: StickerAnalysis,
  dimensions: Pair<Int, Int>?,
  bytes: Int,
  maxSide: Int,
  maxAspect: Double,
  maxBytes: Long,
): String {
  val library = stickerEnabled && analysis.isMeme && analysis.nsfwRisk != "high" &&
    looksLikeSticker(dimensions, maxSide, maxAspect) && bytes <= maxBytes
  return when {
    !library -> StickerStatus.CAPTIONED
    autoApprove && analysis.nsfwRisk == "low" -> StickerStatus.READY
    else -> StickerStatus.PENDING
  }
}

/**
 * 观察路径的图片理解: 下载 → 抢占 hash → 视觉模型打标 → 回写上下文摘要 → 按 [captureStatus] 决定进图库还是只留摘要.
 * 表情收集与看图打标共用这条管线与全局小时预算; 重复图 (撞 hash) 直接用已有摘要回填, 不消耗额度.
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

  fun stickerEnabled(cfg: ChatbotConfig): Boolean = ChatbotSecrets.stickerCaptureEnabled && cfg.stickerCapture

  fun captionEnabled(cfg: ChatbotConfig): Boolean = ChatbotSecrets.imageCaptionEnabled && cfg.imageCaption

  fun captureEnabled(cfg: ChatbotConfig): Boolean = stickerEnabled(cfg) || captionEnabled(cfg)

  suspend fun capture(client: HttpClient, lineId: String, groupId: String, senderId: String, cfg: ChatbotConfig, images: List<TencentImage>) {
    images.take(MAX_IMAGES_PER_MESSAGE).forEach { image ->
      semaphore.withPermit {
        runCatchingCancellable { captureOne(client, lineId, groupId, senderId, cfg, image.url) }
          .onFailure { PluginMain.logger.warn("chatbot 图片抓取失败: ${image.url.take(120)}", it) }
      }
    }
  }

  private suspend fun captureOne(client: HttpClient, lineId: String, groupId: String, senderId: String, cfg: ChatbotConfig, url: String) {
    // 下载上限与回复路径看图一致; stickerMaxBytes 只是入库资格, 不再挡分析.
    val image = downloadImage(client, url, ChatbotSecrets.imageMaxBytes, ChatbotSecrets.imageDownloadTimeoutMillis) ?: return
    val dimensions = probeDimensions(image.bytes)
    // 只开收集没开看图时维持旧的省钱路径: 不够入库资格的图直接放弃, 打了标也没有消费方, 不值得花视觉额度.
    if (!captionEnabled(cfg) &&
      (image.bytes.size > ChatbotSecrets.stickerMaxBytes || !looksLikeSticker(dimensions, ChatbotSecrets.stickerMaxSide, ChatbotSecrets.stickerMaxAspect))
    ) {
      return
    }
    if (!StickerStore.claim(image.sha256, groupId, senderId)) {
      // 重复图: 库里已有摘要 (含 captioned/rejected/deleted 行) 就零成本回填, 分析中/被 release 的行拿不到, 下轮再补.
      if (captionEnabled(cfg)) StickerStore.findSummary(image.sha256)?.let { backfillSummary(lineId, it) }
      return
    }
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

    if (captionEnabled(cfg)) analysis.summary.trim().takeIf { it.isNotEmpty() }?.let { backfillSummary(lineId, it) }
    val status = captureStatus(
      stickerEnabled = stickerEnabled(cfg),
      autoApprove = ChatbotSecrets.stickerAutoApprove,
      analysis = analysis,
      dimensions = dimensions,
      bytes = image.bytes.size,
      maxSide = ChatbotSecrets.stickerMaxSide,
      maxAspect = ChatbotSecrets.stickerMaxAspect,
      maxBytes = ChatbotSecrets.stickerMaxBytes,
    )
    val fileName = if (status == StickerStatus.CAPTIONED) null else StickerFiles.nameFor(image).also {
      try {
        StickerFiles.put(it, image.bytes)
      } catch (t: Throwable) {
        StickerStore.release(image.sha256)
        throw t
      }
    }
    StickerStore.finish(image.sha256, image, dimensions, analysis, status, fileName)
    PluginMain.logger.info("chatbot 图片打标 $status: ${image.sha256.take(12)} ${analysis.tags.joinToString(" ")}")
  }

  private suspend fun backfillSummary(lineId: String, summary: String) {
    runCatchingCancellable { ChatStore.setImageSummary(lineId, summary) }
      .onFailure { PluginMain.logger.warn("回写图片描述失败", it) }
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
