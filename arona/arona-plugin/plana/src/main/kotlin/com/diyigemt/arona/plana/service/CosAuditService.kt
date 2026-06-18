package com.diyigemt.arona.plana.service

import com.diyigemt.arona.plana.Config
import com.diyigemt.arona.plana.PluginMain
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.model.ObjectMetadata
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest
import com.qcloud.cos.region.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.LocalDate

/** 一次图片审查的结果. [score] 取涉黄维度分数(0..100). */
internal class AuditResult(
  val score: Int,
  val label: String?,
  val state: String?,
  val result: Int?,
)

/**
 * 腾讯云图片审查: 将待审图片先上传到 COS, 再以 objectKey 调用同步图片审查(imageAuditingV2),
 * 无论成败都在 finally 删除暂存对象。COSClient 内部带连接池, 全局复用一个实例即可。
 */
internal object CosAuditService {
  private val client by lazy {
    val clientConfig = ClientConfig(Region(Config.region)).apply {
      // 阻塞式 SDK 无法被协程 withTimeoutOrNull 真正打断, 必须在 SDK 层设硬超时,
      // 否则坏网络会长期占住 IO 线程。
      setConnectionTimeout(3000)
      setSocketTimeout(Config.auditTimeoutMillis.toInt())
    }
    COSClient(BasicCOSCredentials(Config.cosSecretId, Config.cosSecretKey), clientConfig)
  }

  /** 凭据/桶任一缺失时视为未配置, 调用方应放行而非拦截。 */
  fun isConfigured(): Boolean =
    Config.cosSecretId.isNotBlank() && Config.cosSecretKey.isNotBlank() && Config.bucket.isNotBlank()

  suspend fun audit(hash: String, bytes: ByteArray): AuditResult = withContext(Dispatchers.IO) {
    val key = buildObjectKey(hash)
    try {
      uploadObject(key, bytes)
      val resp = client.imageAuditingV2(
        ImageAuditingRequest().apply {
          bucketName = Config.bucket
          objectKey = key
          detectType = "Porn"
          // 大图(>5MB)审查需置 "1", 否则腾讯侧会拒绝; 取值范围内统一开启更稳。
          largeImageDetect = "1"
        }
      )
      // 仅当确实拿到涉黄分数才视为成功; 否则抛出走 fail-open, 避免把失败响应当作 0 分缓存导致永久放行。
      val pornScore = resp.pornInfo?.score
        ?: error("图片审查响应无有效分数: state=${resp.state}, result=${resp.result}, label=${resp.label}")
      AuditResult(
        score = pornScore,
        label = resp.label,
        state = resp.state,
        result = resp.result
      )
    } finally {
      runCatching { client.deleteObject(Config.bucket, key) }
        .onFailure { PluginMain.logger.warn("删除 COS 暂存对象失败, key=$key", it) }
    }
  }

  private fun uploadObject(key: String, bytes: ByteArray) {
    val metadata = ObjectMetadata().apply {
      contentLength = bytes.size.toLong()
      contentType = "application/octet-stream"
    }
    ByteArrayInputStream(bytes).use { input ->
      client.putObject(Config.bucket, key, input, metadata)
    }
  }

  private fun buildObjectKey(hash: String): String {
    val prefix = Config.cosPathPrefix.trim('/').takeIf { it.isNotBlank() }
    return listOfNotNull(prefix, LocalDate.now().toString(), "$hash.img").joinToString("/")
  }
}
