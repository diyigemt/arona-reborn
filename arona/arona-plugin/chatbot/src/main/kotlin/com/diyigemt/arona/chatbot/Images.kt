package com.diyigemt.arona.chatbot

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** 一张已下载并验明是图片的字节. [mime] 由魔数嗅探得到 (不信任 Content-Type), [sha256] 为内容指纹. */
internal class DownloadedImage(val bytes: ByteArray, val mime: String) {
  val sha256: String by lazy { sha256Hex(bytes) }
  val extension: String get() = mime.substringAfter('/').replace("jpeg", "jpg")

  @OptIn(ExperimentalEncodingApi::class)
  fun dataUrl(): String = "data:$mime;base64,${Base64.encode(bytes)}"
}

/** 只认视觉模型支持的四种格式: JPEG / PNG / GIF / WebP. 其它 (含非图片) 返回 null. */
internal fun sniffMime(bytes: ByteArray): String? {
  fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xff)
  return when {
    at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
    at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "image/png"
    at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "image/gif"
    at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
      at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
    else -> null
  }
}

/**
 * 只读头部取宽高 (不解码像素). JDK 的 ImageIO 没有 WebP reader, WebP 返回 null, 调用方按 "无法判定" 放行.
 * ponytail: 需要 WebP 尺寸时手解 VP8/VP8L/VP8X 头 (各约 10 行) 或引入 webp-imageio.
 */
internal fun probeDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
  ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
    try {
      reader.input = input
      reader.getWidth(0) to reader.getHeight(0)
    } finally {
      reader.dispose()
    }
  }
}.getOrNull()

/** 表情包启发式: 最长边与长宽比都在阈值内. 尺寸未知 (null) 时放行, 交给模型判 is_meme. */
internal fun looksLikeSticker(dimensions: Pair<Int, Int>?, maxSide: Int, maxAspect: Double): Boolean {
  val (w, h) = dimensions ?: return true
  if (w <= 0 || h <= 0) return false
  return maxOf(w, h) <= maxSide && maxOf(w, h).toDouble() / minOf(w, h) <= maxAspect
}

/**
 * 下载并校验一张图片. 超过 [maxBytes] (Content-Length 预检, 或流式读到上限仍未结束)、非 200、魔数不是图片 → null.
 * 流式限长: 不把超大响应整个读进内存再判断.
 */
internal suspend fun downloadImage(client: HttpClient, url: String, maxBytes: Long, timeoutMillis: Long): DownloadedImage? =
  client.prepareGet(url) { timeout { requestTimeoutMillis = timeoutMillis } }.execute { resp ->
    if (resp.status != HttpStatusCode.OK) return@execute null
    if ((resp.contentLength() ?: 0L) > maxBytes) return@execute null
    val bytes = resp.bodyAsChannel().readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) return@execute null
    sniffMime(bytes)?.let { DownloadedImage(bytes, it) }
  }

private fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
