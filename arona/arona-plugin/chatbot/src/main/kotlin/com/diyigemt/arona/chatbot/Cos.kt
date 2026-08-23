package com.diyigemt.arona.chatbot

import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.model.ObjectMetadata
import com.qcloud.cos.region.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 表情图片的持久化存储 (私有桶). 出站不用公网 URL: 取字节后经 [com.diyigemt.arona.communication.contact.Contact.uploadImage]
 * 交给 QQ, 桶不必开公共读. COSClient 是阻塞 SDK, 放 IO 线程并在 SDK 层设硬超时 (协程取消打不断它).
 */
internal object StickerCos {
  private const val SOCKET_TIMEOUT_MILLIS = 8_000

  private val client by lazy {
    val config = ClientConfig(Region(ChatbotSecrets.cosRegion)).apply {
      setConnectionTimeout(3_000)
      setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
    }
    COSClient(BasicCOSCredentials(ChatbotSecrets.cosSecretId, ChatbotSecrets.cosSecretKey), config)
  }

  fun isConfigured(): Boolean =
    ChatbotSecrets.cosSecretId.isNotBlank() && ChatbotSecrets.cosSecretKey.isNotBlank() && ChatbotSecrets.cosBucket.isNotBlank()

  fun keyFor(image: DownloadedImage): String =
    listOfNotNull(ChatbotSecrets.cosPathPrefix.trim('/').takeIf { it.isNotBlank() }, "${image.sha256}.${image.extension}").joinToString("/")

  suspend fun put(key: String, image: DownloadedImage) = withContext(Dispatchers.IO) {
    val metadata = ObjectMetadata().apply {
      contentLength = image.bytes.size.toLong()
      contentType = image.mime
    }
    ByteArrayInputStream(image.bytes).use { client.putObject(ChatbotSecrets.cosBucket, key, it, metadata) }
    Unit
  }

  suspend fun get(key: String): ByteArray = withContext(Dispatchers.IO) {
    client.getObject(ChatbotSecrets.cosBucket, key).objectContent.use { it.readBytes() }
  }
}
