@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.communication.message

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class MediaInfo(
  @ProtoNumber(1)
  val data: FileInfoData,
  @ProtoNumber(2)
  val status: Int,
)

@Serializable
data class FileInfoData(
  @SerialName("file_info")
  @ProtoNumber(2)
  val fileInfo: FileInfo,
  @SerialName("url_info")
  @ProtoNumber(3)
  val urlInfo: UrlInfo,
)

@Serializable
data class FileInfo(
  @ProtoNumber(1)
  val fileInfo: FileInfo1,
  @ProtoNumber(2)
  val fileInfoUnknown: FileInfoUnknown,
)

@Serializable
data class FileInfo1(
  @ProtoNumber(1)
  val attr: FileInfoUnknown3,
  @ProtoNumber(2)
  val urlInfo: FileInfoUrlInfo,
  @ProtoNumber(5)
  val unknown1: Int = 0,
  @ProtoNumber(6)
  val group: FileInfoGroup,
)

@Serializable
data class FileInfoGroup(
  @ProtoNumber(102)
  val unknown1: Int,
  @ProtoNumber(200)
  val unknown2: Int,
  @ProtoNumber(202)
  val groupInfo: FileInfoGroupInfo,
)

@Serializable
data class FileInfoGroupInfo(
  @ProtoNumber(1)
  val groupUid: Int,
)

@Serializable
data class FileInfoUrlInfo(
  @ProtoNumber(1)
  val path: String,
  @ProtoNumber(2)
  val search: FileInfoUrlInfoSearch,
  @ProtoNumber(3)
  val host: String,
)

@Serializable
data class FileInfoUrlInfoSearch(
  @ProtoNumber(1)
  val s1: String,
  @ProtoNumber(2)
  val s2: String,
  @ProtoNumber(3)
  val s3: String,
)

@Serializable
data class FileInfoUnknown3(
  @ProtoNumber(1)
  val attr: FileInfoAttr,
  @ProtoNumber(2)
  val id: String,
  @ProtoNumber(3)
  val unknown1: Int,
  @ProtoNumber(4)
  val timestamp: Int,
  @ProtoNumber(5)
  val unknown3: Int,
  @ProtoNumber(7)
  val unknown4: Int,
)

@Serializable
data class FileInfoAttr(
  @ProtoNumber(1)
  val size: Int,
  @ProtoNumber(2)
  val md5: String,
  @ProtoNumber(3)
  val sh1: String,
  @ProtoNumber(4)
  val name: String,
  @ProtoNumber(5)
  val unknown1: FileInfoAttrUnknown,
  @ProtoNumber(6)
  val width: Int,
  @ProtoNumber(7)
  val height: Int,
  @ProtoNumber(9)
  val unknown2: Int,
)

@Serializable
data class FileInfoAttrUnknown(
  @ProtoNumber(1)
  val unknown1: Int,
  @ProtoNumber(2)
  val unknown2: Int,
)

@Serializable
data class FileInfoUnknown(
  @ProtoNumber(1)
  val unknown2: FileInfoUnknown2,
  @ProtoNumber(10)
  val unknown3: Int,
)

@Serializable
data class FileInfoUnknown2(
  @ProtoNumber(1001)
  val unknown4: Int,
  @ProtoNumber(1002)
  val unknown5: Int,
  @ProtoNumber(1003)
  val unknown6: Int,
)

@Serializable
data class UrlInfo(
  @SerialName("file_name")
  @ProtoNumber(2)
  val fileName: String,
  @ProtoNumber(7)
  val unknown1: Int,
  @ProtoNumber(10)
  val unknown2: Int,
  @ProtoNumber(13)
  val unknown3: String,
  @ProtoNumber(20)
  val unknown4: Int,
  @ProtoNumber(22)
  val unknown5: Int,
  @ProtoNumber(23)
  val unknown6: Int,
  @ProtoNumber(25)
  val unknown7: Int,
  @ProtoNumber(26)
  val unknown8: Int,
  @ProtoNumber(34)
  val download: Unknown9,
)

@Serializable
data class Unknown9(
  @ProtoNumber(1)
  val unknown10: Int,
  @ProtoNumber(9)
  val unknown11: Unknown11,
  @SerialName("full_path")
  @ProtoNumber(30)
  val fullPath: String,
)

@Serializable
class Unknown11

@Serializable
data class MediaUuid(
  val id: String,
  val unknown1: ByteArray,
  val unknown2: Int,
  val unknown3: Int,
  val unknown4: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as MediaUuid
    if (id != other.id) return false
    if (!unknown1.contentEquals(other.unknown1)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + unknown1.contentHashCode()
    return result
  }
}

@OptIn(ExperimentalSerializationApi::class)
private val protobuf by lazy {
  ProtoBuf { encodeDefaults = true }
}

@OptIn(ExperimentalEncodingApi::class)
fun getMediaUrlFromMediaInfo(data: String): String {
  val decoded = protobuf
    .decodeFromByteArray<MediaInfo>(Base64.decode(data))
  return "http://" +
    decoded.data.fileInfo.fileInfo.urlInfo.host +
    decoded.data.urlInfo.download.fullPath
}
