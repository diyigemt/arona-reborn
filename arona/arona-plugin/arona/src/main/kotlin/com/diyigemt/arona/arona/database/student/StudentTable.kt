package com.diyigemt.arona.arona.database.student

import com.diyigemt.arona.arona.database.Database
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

@Database
object StudentTable : IntIdTable(name = "Student") {
  val name = varchar("name", length = 50)
  val limit = enumerationByName<StudentLimitType>("limit", length = 20).clientDefault { StudentLimitType.Permanent }
  val rarity = enumerationByName<StudentRarity>("rarity", length = 20).clientDefault { StudentRarity.SSR }
  val headFileName = varchar("head_file_name", length = 255)
}

class StudentSchema (id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<StudentSchema>(StudentTable)
  var name by StudentTable.name
  var limit by StudentTable.limit
  var rarity by StudentTable.rarity
  var headFileName by StudentTable.headFileName
  override fun toString(): String {
    return "StudentSchema(name=$name, limit=$limit, rarity=$rarity, head=$headFileName)"
  }
}

@Serializable(with = StudentRaritySerializer::class)
enum class StudentRarity {
  R,
  SR,
  SSR;

  fun toInt() = list.indexOfFirst { it == this }

  companion object {
    private val list = entries.toList()
    fun fromInt(i: Int) = list.getOrNull(i) ?: SSR
  }
}
object StudentRaritySerializer : KSerializer<StudentRarity> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StudentRaritySerializer", PrimitiveKind.INT)
  override fun deserialize(decoder: Decoder) = StudentRarity.fromInt(decoder.decodeInt())
  override fun serialize(encoder: Encoder, value: StudentRarity) = encoder.encodeInt(value.toInt())
}

@Serializable(with = StudentLimitTypeSerializer::class)
enum class StudentLimitType {
  Unique, // 限定
  Event, // 活动
  Permanent; // 常驻

  companion object {
    private val map = entries.associateBy { it.name }
    fun fromString(name: String) = map.getOrDefault(name, Permanent)
  }
}
object StudentLimitTypeSerializer : KSerializer<StudentLimitType> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StudentLimitTypeSerializer", PrimitiveKind.STRING)
  override fun deserialize(decoder: Decoder) = StudentLimitType.fromString(decoder.decodeString())
  override fun serialize(encoder: Encoder, value: StudentLimitType) = encoder.encodeString(value.name)
}
