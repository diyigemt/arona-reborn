package com.diyigemt.arona.arona.database.image

import com.diyigemt.arona.arona.database.Database
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

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
object ResourceTable: LongIdTable(name = "Resource") {
  val name = char("name", 255)
  val hash = char("hash", 255)
  val content = char("content", 255)
  val type = enumerationByName<ResourceType>("type", 10)
}

class ImageTableModel(id: EntityID<Long>): LongEntity(id) {
  companion object: LongEntityClass<ImageTableModel>(ResourceTable)
  var name by ResourceTable.name
  var hash by ResourceTable.hash
  var content by ResourceTable.content
  var type by ResourceTable.type
}
