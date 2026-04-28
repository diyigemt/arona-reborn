package com.diyigemt.arona.database

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.bson.BsonReader
import org.bson.BsonType
import org.bson.BsonWriter
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.CodecProvider
import org.bson.codecs.configuration.CodecRegistry

/**
 * 为 kotlinx.serialization 的 [JsonElement] 补齐 BSON codec.
 *
 * 当前仓库里唯一持久化命中点是 kivotos 的 `ItemTemplateDocument.effectPayload`,
 * 它承载 effect 多态 JSON 载荷, 业务层用 `Json.decodeFromJsonElement(...)` 在 use site
 * 自行解码. 因此这里采用 "JSON 树 <-> BSON 原生结构" 的递归映射, 不退化成整串 JSON 文本,
 * 以保留 mongo shell 可读性与未来聚合查询能力.
 *
 * 映射规则:
 *  - JsonObject    <-> BSON Document
 *  - JsonArray     <-> BSON Array
 *  - JsonNull      <-> BSON Null
 *  - JsonPrimitive <-> BSON String / Boolean / Int32 / Int64 / Double
 *
 * 关键约束:
 *  - 严格保留 [JsonPrimitive.isString]: `"42"` 与 `42` 不能合并.
 *  - 对无法可靠映射为有限 Long / Double 的 "非字符串数字 primitive" 直接失败,
 *    避免静默退化成字符串. 当模板里需要超大数或高精度数, 应在 JSON 里显式写为字符串.
 *  - decode 仅接受 JSON 兼容 BSON 类型; DateTime / ObjectId / Binary / Decimal128 等一律拒绝,
 *    防止 effectPayload 在数据流向外泄漏成不可由 kotlinx 反序列化的非 JSON 值.
 */
internal object KotlinxJsonElementCodecProvider : CodecProvider {
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> get(clazz: Class<T>, registry: CodecRegistry): Codec<T>? =
    if (clazz == JsonElement::class.java) JsonElementCodec as Codec<T> else null
}

private object JsonElementCodec : Codec<JsonElement> {
  override fun encode(writer: BsonWriter, value: JsonElement, encoderContext: EncoderContext) {
    when (value) {
      JsonNull -> writer.writeNull()
      is JsonObject -> encodeObject(writer, value, encoderContext)
      is JsonArray -> encodeArray(writer, value, encoderContext)
      is JsonPrimitive -> encodePrimitive(writer, value)
    }
  }

  override fun decode(reader: BsonReader, decoderContext: DecoderContext): JsonElement = when (reader.currentBsonType) {
    BsonType.DOCUMENT -> decodeObject(reader, decoderContext)
    BsonType.ARRAY -> decodeArray(reader, decoderContext)
    BsonType.NULL -> {
      reader.readNull()
      JsonNull
    }
    BsonType.STRING -> JsonPrimitive(reader.readString())
    BsonType.BOOLEAN -> JsonPrimitive(reader.readBoolean())
    BsonType.INT32 -> JsonPrimitive(reader.readInt32())
    BsonType.INT64 -> JsonPrimitive(reader.readInt64())
    BsonType.DOUBLE -> JsonPrimitive(reader.readDouble())
    else -> throw CodecConfigurationException(
      "无法把 BSON ${reader.currentBsonType} 解码为 JsonElement, 仅支持 JSON 兼容类型"
    )
  }

  override fun getEncoderClass(): Class<JsonElement> = JsonElement::class.java
}

private fun encodeObject(writer: BsonWriter, value: JsonObject, encoderContext: EncoderContext) {
  writer.writeStartDocument()
  value.forEach { (key, element) ->
    writer.writeName(key)
    JsonElementCodec.encode(writer, element, encoderContext)
  }
  writer.writeEndDocument()
}

private fun encodeArray(writer: BsonWriter, value: JsonArray, encoderContext: EncoderContext) {
  writer.writeStartArray()
  value.forEach { element ->
    JsonElementCodec.encode(writer, element, encoderContext)
  }
  writer.writeEndArray()
}

private fun encodePrimitive(writer: BsonWriter, value: JsonPrimitive) {
  if (value === JsonNull) {
    writer.writeNull()
    return
  }
  if (value.isString) {
    writer.writeString(value.content)
    return
  }
  value.booleanOrNull?.let {
    writer.writeBoolean(it)
    return
  }
  val content = value.content
  content.toIntOrNull()?.let {
    writer.writeInt32(it)
    return
  }
  content.toLongOrNull()?.let {
    writer.writeInt64(it)
    return
  }
  content.toDoubleOrNull()?.let {
    if (it.isFinite()) {
      writer.writeDouble(it)
      return
    }
  }
  throw CodecConfigurationException(
    "JsonPrimitive '$content' 不是可安全映射到 BSON 的有限 JSON 数字; " +
      "若它本应是字符串, 请在源数据里显式写成 JSON string"
  )
}

private fun decodeObject(reader: BsonReader, decoderContext: DecoderContext): JsonObject {
  reader.readStartDocument()
  val content = linkedMapOf<String, JsonElement>()
  while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
    content[reader.readName()] = JsonElementCodec.decode(reader, decoderContext)
  }
  reader.readEndDocument()
  return JsonObject(content)
}

private fun decodeArray(reader: BsonReader, decoderContext: DecoderContext): JsonArray {
  reader.readStartArray()
  val content = mutableListOf<JsonElement>()
  while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
    content += JsonElementCodec.decode(reader, decoderContext)
  }
  reader.readEndArray()
  return JsonArray(content)
}
