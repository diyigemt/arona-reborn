package com.diyigemt.arona.rollpig.service

import com.diyigemt.arona.communication.contact.Contact
import com.diyigemt.arona.communication.image.ImageUploadCache
import com.diyigemt.arona.communication.message.TencentImage
import java.security.MessageDigest

/**
 * 预生成卡片的上传凭证缓存。
 *
 * 缓存/复用/过期/并发去重/容量与清理统一交给框架 [ImageUploadCache] (数据库持久化, 同 AppID 同场景共享,
 * 故同一只猪在所有群只需上传一次, 不再随群数量膨胀)。
 *
 * 缓存键 = `pigId + 内容指纹`: 卡片图片可离线替换 (换图免重打包), 内容指纹保证换图后旧凭证自然失效,
 * 不会继续命中旧图。
 */
internal object CardImageService {
  private const val NAMESPACE = "rollpig"

  suspend fun getImage(subject: Contact, pigId: String, bytes: ByteArray): TencentImage {
    val key = "$pigId@${fingerprint(bytes)}"
    return ImageUploadCache.getOrUpload(subject, NAMESPACE, key) {
      subject.uploadImage(bytes)
    }
  }

  /** 取卡片内容 SHA-256 前 16 个十六进制字符作为短指纹, 区分换图版本且不撑长缓存键。 */
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
