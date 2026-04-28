package com.diyigemt.arona.database

import com.mongodb.MongoClientSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.BsonInt32
import org.bson.BsonInt64
import org.bson.BsonObjectId
import org.bson.BsonString
import org.bson.codecs.Codec
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecConfigurationException
import org.bson.codecs.configuration.CodecRegistries.fromProviders
import org.bson.codecs.configuration.CodecRegistries.fromRegistries
import org.bson.codecs.configuration.CodecRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 单元测试: 直接验证 [KotlinxJsonElementCodecProvider] 的 round-trip 与边界行为.
 *
 * 重点关注两类语义不变性:
 *  - JSON 树结构 (Object / Array / Primitive / Null) 完全保留
 *  - JsonPrimitive 的 isString 标记不能被合并 ("42" 与 42 必须分开)
 */
class KotlinxJsonElementCodecProviderTest {

  private val registry: CodecRegistry = fromRegistries(
    fromProviders(KotlinxJsonElementCodecProvider),
    MongoClientSettings.getDefaultCodecRegistry(),
  )

  private val codec: Codec<JsonElement> = registry.get(JsonElement::class.java)

  private fun roundTrip(value: JsonElement): JsonElement {
    val doc = BsonDocument()
    BsonDocumentWriter(doc).use { w ->
      w.writeStartDocument()
      w.writeName("v")
      codec.encode(w, value, EncoderContext.builder().build())
      w.writeEndDocument()
    }
    return BsonDocumentReader(doc).use { r ->
      r.readStartDocument()
      r.readName()
      codec.decode(r, DecoderContext.builder().build())
    }
  }

  @Test
  fun `nested object with mixed primitives round-trips faithfully`() {
    val original = buildJsonObject {
      put("name", JsonPrimitive("Aris"))
      put("level", JsonPrimitive(99))
      put("hp", JsonPrimitive(123_456_789_012L))
      put("crit", JsonPrimitive(0.25))
      put("active", JsonPrimitive(true))
      put("tag", JsonNull)
      put(
        "rewards",
        buildJsonArray {
          add(JsonPrimitive("gold"))
          add(JsonPrimitive(100))
          add(buildJsonObject { put("nested", JsonPrimitive("yes")) })
        },
      )
    }
    assertEquals(original, roundTrip(original))
  }

  @Test
  fun `JsonPrimitive isString boundary preserved across BSON`() {
    val asText = JsonPrimitive("42")
    val asNumber = JsonPrimitive(42)
    val decodedText = roundTrip(asText) as JsonPrimitive
    val decodedNumber = roundTrip(asNumber) as JsonPrimitive
    assertTrue(decodedText.isString, "decoded \"42\" must keep isString=true")
    assertEquals("42", decodedText.content)
    assertTrue(!decodedNumber.isString, "decoded 42 must keep isString=false")
    assertEquals("42", decodedNumber.content)
  }

  @Test
  fun `JsonPrimitive boolean preserved`() {
    listOf(JsonPrimitive(true), JsonPrimitive(false)).forEach {
      val decoded = roundTrip(it) as JsonPrimitive
      assertTrue(!decoded.isString)
      assertEquals(it.content, decoded.content)
    }
  }

  @Test
  fun `string content that looks like a boolean stays a string`() {
    val original = JsonPrimitive("true")
    val decoded = roundTrip(original) as JsonPrimitive
    assertTrue(decoded.isString)
    assertEquals("true", decoded.content)
  }

  @Test
  fun `JsonNull at top level round-trips`() {
    val decoded = roundTrip(JsonNull)
    assertEquals(JsonNull, decoded)
  }

  @Test
  fun `empty object and empty array round-trip`() {
    assertEquals(JsonObject(emptyMap()), roundTrip(JsonObject(emptyMap())))
    assertEquals(JsonArray(emptyList()), roundTrip(JsonArray(emptyList())))
  }

  @Test
  fun `object property insertion order is preserved`() {
    val original = buildJsonObject {
      put("z", JsonPrimitive(1))
      put("a", JsonPrimitive(2))
      put("m", JsonPrimitive(3))
    }
    val decoded = roundTrip(original) as JsonObject
    assertEquals(listOf("z", "a", "m"), decoded.keys.toList())
  }

  @Test
  fun `decode rejects unsupported BSON types`() {
    val doc = BsonDocument("v", BsonObjectId())
    assertFailsWith<CodecConfigurationException> {
      BsonDocumentReader(doc).use { r ->
        r.readStartDocument()
        r.readName()
        codec.decode(r, DecoderContext.builder().build())
      }
    }
  }

  @Test
  fun `decode accepts both Int32 and Int64 from existing BSON storage`() {
    listOf(BsonInt32(7), BsonInt64(7L)).forEach { value ->
      val doc = BsonDocument("v", value)
      val decoded = BsonDocumentReader(doc).use { r ->
        r.readStartDocument()
        r.readName()
        codec.decode(r, DecoderContext.builder().build())
      } as JsonPrimitive
      assertTrue(!decoded.isString)
      assertEquals(7L, decoded.content.toLong())
    }
  }

  @Test
  fun `decode of BSON String yields isString-tagged primitive`() {
    val doc = BsonDocument("v", BsonString("hello"))
    val decoded = BsonDocumentReader(doc).use { r ->
      r.readStartDocument()
      r.readName()
      codec.decode(r, DecoderContext.builder().build())
    } as JsonPrimitive
    assertTrue(decoded.isString)
    assertEquals("hello", decoded.content)
  }

  @Test
  fun `applyAronaCodecs wires the JsonElement provider into MongoClientSettings`() {
    val settings = MongoClientSettings.builder()
      .applyAronaCodecs()
      .build()
    assertEquals(
      JsonElement::class.java,
      settings.codecRegistry.get(JsonElement::class.java).encoderClass,
    )
  }
}
