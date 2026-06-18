package com.diyigemt.arona.plana.service

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest

/** 已下载且达到尺寸阈值的图片. [sha256] 为内容指纹, 兼作审查结果缓存键. */
internal class DownloadedImage(val bytes: ByteArray, val sha256: String)

internal object ImageDownloadService {
  /**
   * 下载图片并判断是否需要审查.
   *
   * 流程: 先 HEAD 取 Content-Length, 能确定小于阈值就直接跳过(省一次 GET); 否则 GET 全量字节,
   * 以真实字节数为准再判一次。返回 null 表示"无需/无法审查"(尺寸不足或下载失败), 调用方按放行处理。
   *
   * 注意: 腾讯图片直链的 Content-Length 不保证存在/准确, 仅作为小图快速过滤, 不作唯一依据。
   */
  suspend fun download(client: HttpClient, url: String, minBytes: Int): DownloadedImage? {
    val resp = client.get(url)
    if (resp.status != HttpStatusCode.OK) {
      error("download image failed: GET $url -> ${resp.status}")
    }
    val bytes = resp.readRawBytes()
    if (bytes.size < minBytes) return null
    return DownloadedImage(bytes, sha256Hex(bytes))
  }

  private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(digest.size * 2) {
      digest.forEach { b ->
        val v = b.toInt() and 0xff
        if (v < 16) append('0')
        append(v.toString(16))
      }
    }
  }
}
