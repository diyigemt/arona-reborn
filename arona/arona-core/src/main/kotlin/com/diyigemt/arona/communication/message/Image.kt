package com.diyigemt.arona.communication.message

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

interface TencentImage : TencentResource, Message {
  val height: Int
  val width: Int
  val url: String
}

// 由客户端通过文件上传接口获取到的image实例
data class TencentOfflineImage(
  override val resourceId: String,
  override val resourceUuid: String,
  override val ttl: Long,
  override val url: String = "",
) : TencentImage {
  override fun toString() = serialization()
  override fun serialization() = "[tencent:image:$url]"
  override val height: Int = 0
  override val width: Int = 0
  override val size: Long = 0L

  /**
   * 获取tx服务器下载直链
   */
  @JvmName("get_url")
  fun getUrl() = getMediaUrlFromMediaInfo(resourceId)

  companion object {
    private val matcher = Regex("^\\[tencent:image:(\\w+)]$")
    fun String.toTencentImage(): TencentOfflineImage? {
      val matchResult = matcher.matchEntire(this) ?: return null
      return TencentOfflineImage(matchResult.groupValues[1], "", 0L)
    }

    val serializer = object : KSerializer<TencentOfflineImage> {
      override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("TencentImage", PrimitiveKind.STRING)

      override fun deserialize(decoder: Decoder): TencentOfflineImage =
        decoder.decodeString().toTencentImage() ?: TencentOfflineImage("", "", 0L)

      override fun serialize(encoder: Encoder, value: TencentOfflineImage) = encoder.encodeString(value.serialization())

    }
  }
}

// 由接收到的消息序列化成的image对象
data class TencentOnlineImage(
  override val resourceId: String,
  override val resourceUuid: String,
  override val ttl: Long,
  override val url: String = "",
) : TencentImage {
  override fun toString() = serialization()
  override fun serialization() = "[tencent:image:$url]"
  override val height: Int = 0
  override val width: Int = 0
  override val size: Long = 0L
}

// 简单的频道图片对象
data class TencentGuildImage(
  override val url: String,
  override val resourceId: String = "",
  override val resourceUuid: String = "",
  override val ttl: Long = 0L,
) : TencentImage {
  override fun toString() = serialization()
  override fun serialization() = "[tencent:image:$url]"
  override val height: Int = 0
  override val width: Int = 0
  override val size: Long = 0L
}

data class TencentGuildLocalImage(
  override val url: String = "",
  override val resourceId: String = "",
  override val resourceUuid: String = "",
  override val ttl: Long = 0L,
  override val raw: ByteArray,
) : TencentImage, Media {
  override fun toString() = serialization()
  override fun serialization() = "[tencent:image:$url]"
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TencentGuildLocalImage

    if (url != other.url) return false
    if (resourceId != other.resourceId) return false
    if (resourceUuid != other.resourceUuid) return false
    if (ttl != other.ttl) return false
    if (!raw.contentEquals(other.raw)) return false
    if (height != other.height) return false
    if (width != other.width) return false
    if (size != other.size) return false

    return true
  }

  override fun hashCode(): Int {
    var result = url.hashCode()
    result = 31 * result + resourceId.hashCode()
    result = 31 * result + resourceUuid.hashCode()
    result = 31 * result + ttl.hashCode()
    result = 31 * result + raw.contentHashCode()
    result = 31 * result + height
    result = 31 * result + width
    result = 31 * result + size.hashCode()
    return result
  }

  override val height: Int = 0
  override val width: Int = 0
  override val size: Long = 0L
}
