package com.diyigemt.arona.plana.service

import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.image.ImageUploadCache
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.plana.PluginMain
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置图片资源(plana.jpg / h.jpg)的上传缓存。
 *
 * 上传凭证的缓存/复用/过期/并发去重统一交给框架 [ImageUploadCache] (数据库持久化, 同 AppID 同场景共享)。
 * 本类只负责: 读取 jar 内静态资源, 并用"资源名 + 内容指纹"作为缓存键——内容指纹保证更换 jar 内图片后
 * 旧凭证自然失效, 不会继续命中旧图。
 */
internal object ImageAssetService {
  private const val NAMESPACE = "plana"

  private class LoadedResource(val bytes: ByteArray, val cacheKey: String)

  // jar 内资源不会在运行期变化, 字节与指纹按资源名缓存一次, 避免每次取图都重读 + 重算哈希。
  private val resources = ConcurrentHashMap<String, LoadedResource>()

  suspend fun getImage(subject: Contact, assetName: String): TencentImage {
    val resource = resources.computeIfAbsent(assetName) {
      val bytes = readResource(assetName)
      LoadedResource(bytes, "$assetName@${fingerprint(bytes)}")
    }
    return ImageUploadCache.getOrUpload(subject, NAMESPACE, resource.cacheKey) {
      subject.uploadImage(resource.bytes)
    }
  }

  private fun readResource(assetName: String): ByteArray =
    (PluginMain::class.java.getResourceAsStream("/$assetName")
      ?: error("资源缺失: /$assetName, 请放入 src/main/resources"))
      .use { it.readBytes() }

  /** 取内容 SHA-256 前 16 个十六进制字符作为短指纹, 足够区分图片版本且不撑长缓存键。 */
  private fun fingerprint(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(16) {
      for (i in 0 until 8) {
        val v = digest[i].toInt() and 0xff
        if (v < 16) append('0')
        append(v.toString(16))
      }
    }
  }
}
