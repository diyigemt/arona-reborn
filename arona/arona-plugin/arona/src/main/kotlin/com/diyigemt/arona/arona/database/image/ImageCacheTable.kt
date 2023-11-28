package com.diyigemt.arona.arona.database.image

import com.diyigemt.arona.arona.database.Database
import com.diyigemt.arona.communication.message.TencentImage
import com.diyigemt.arona.communication.message.TencentOfflineImage
import com.diyigemt.arona.utils.currentTimestamp
import com.diyigemt.arona.utils.now
import com.diyigemt.arona.utils.toDateTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and

@Serializable(with = ResourceTypeAsStringSerializer::class)
enum class ResourceType(val display: String) {
  NULL("null"),  // 错误
  FILE("file"),  // cdn文件
  PLAIN("plain"); // 纯文本

  companion object {
    private val ResourceTypeMap: Map<String, ResourceType> = entries.associateBy { it.display }
    fun fromValue(value: String) = ResourceTypeMap[value] ?: NULL
  }
}

object ResourceTypeAsStringSerializer : KSerializer<ResourceType> {
  override val descriptor = PrimitiveSerialDescriptor("ResourceType", PrimitiveKind.STRING)
  override fun serialize(encoder: Encoder, value: ResourceType) = encoder.encodeString(value.display)
  override fun deserialize(decoder: Decoder) = ResourceType.fromValue(decoder.decodeString())
}

@Database
object ImageCacheTable : IntIdTable(name = "ImageCache") {
  val name = text("name")
  val hash = text("hash")
  val resourceId = text("resource_id")
  val expired = text("expired") // yyyy-MM-dd HH:mm:ss
}

class ImageCacheSchema(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<ImageCacheSchema>(ImageCacheTable) {
    fun findImage(name: String, hash: String) =
      ImageCacheSchema.find { (ImageCacheTable.name eq name) and (ImageCacheTable.hash eq hash) }.firstOrNull()?.toTencentImage()
  }

  var hash by ImageCacheTable.hash
  var name by ImageCacheTable.name
  var resourceId by ImageCacheTable.resourceId
  var expired by ImageCacheTable.expired

  fun toTencentImage() : TencentImage = TencentOfflineImage(
    resourceId,
    "",
    0,
  )
}

fun TencentImage.update(name: String, hash: String) {
  when (val im = ImageCacheSchema.find { ImageCacheTable.name eq name }.firstOrNull()) {
    is ImageCacheSchema -> {
      im.name = name
      im.hash = hash
      im.resourceId = this@update.resourceId
      im.expired = now().plus(this@update.ttl, DateTimeUnit.SECOND).toDateTime()
    }
    else -> {
      ImageCacheSchema.new {
        this@new.name = name
        this@new.hash = hash
        this@new.resourceId = this@update.resourceId
        this.expired = now().plus(this@update.ttl, DateTimeUnit.SECOND).toDateTime()
      }
    }
  }
}