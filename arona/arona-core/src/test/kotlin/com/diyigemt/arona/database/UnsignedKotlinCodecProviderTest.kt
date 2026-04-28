package com.diyigemt.arona.database

import com.mongodb.MongoClientSettings
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.BsonInt32
import org.bson.BsonInt64
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

/**
 * 单元测试: 直接验证 [UnsignedKotlinCodecProvider] 中四个 codec 的 round-trip 与边界行为.
 *
 * 没有跨进 bson-kotlin 的 [org.bson.codecs.kotlin.DataClassCodec] 来做集成测试, 是因为
 * `bson-kotlin` 在 mongodb-driver-kotlin-coroutine 中是 runtimeOnly 依赖, 不在
 * testCompileClasspath 上. provider 与 DataClassCodec 的衔接由 mongo driver 自身保证;
 * 我们这里只确保自定义 codec 的语义正确, 这就足以闭合本次故障的修复.
 */
class UnsignedKotlinCodecProviderTest {

  private val registry: CodecRegistry = fromRegistries(
    fromProviders(UnsignedKotlinCodecProvider),
    MongoClientSettings.getDefaultCodecRegistry(),
  )

  private inline fun <reified T : Any> roundTrip(codec: Codec<T>, value: T): T {
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
  fun `UInt round-trips for typical kivotos values`() {
    val codec = registry.get(UInt::class.java)
    listOf(0u, 1u, 100_000_000u, UInt.MAX_VALUE - 1u, UInt.MAX_VALUE).forEach { v ->
      assertEquals(v, roundTrip(codec, v), "round-trip failed for $v")
    }
  }

  @Test
  fun `UInt above Int_MAX_VALUE round-trips losslessly via bit-preserving Int32`() {
    val codec = registry.get(UInt::class.java)
    val original = Int.MAX_VALUE.toUInt() + 7u
    assertEquals(original, roundTrip(codec, original))
  }

  @Test
  fun `ULong round-trips across the full range`() {
    val codec = registry.get(ULong::class.java)
    listOf(0uL, 1uL, Long.MAX_VALUE.toULong(), Long.MAX_VALUE.toULong() + 1uL, ULong.MAX_VALUE).forEach { v ->
      assertEquals(v, roundTrip(codec, v), "round-trip failed for $v")
    }
  }

  @Test
  fun `UShort and UByte round-trip across full range boundaries`() {
    val uShortCodec = registry.get(UShort::class.java)
    val uByteCodec = registry.get(UByte::class.java)
    listOf(UShort.MIN_VALUE, 1u.toUShort(), UShort.MAX_VALUE).forEach { v ->
      assertEquals(v, roundTrip(uShortCodec, v))
    }
    listOf(UByte.MIN_VALUE, 1u.toUByte(), UByte.MAX_VALUE).forEach { v ->
      assertEquals(v, roundTrip(uByteCodec, v))
    }
  }

  @Test
  fun `UInt codec accepts both Int32 and Int64 storage formats for forward compat`() {
    val codec = registry.get(UInt::class.java)
    val asInt32 = BsonDocument("v", BsonInt32(42))
    val asInt64 = BsonDocument("v", BsonInt64(42L))
    listOf(asInt32, asInt64).forEach { doc ->
      val decoded = BsonDocumentReader(doc).use { r ->
        r.readStartDocument()
        r.readName()
        codec.decode(r, DecoderContext.builder().build())
      }
      assertEquals(42u, decoded)
    }
  }

  @Test
  fun `UShort decode rejects out-of-range value`() {
    val codec = registry.get(UShort::class.java)
    val doc = BsonDocument("v", BsonInt32(70000))
    assertFailsWith<CodecConfigurationException> {
      BsonDocumentReader(doc).use { r ->
        r.readStartDocument()
        r.readName()
        codec.decode(r, DecoderContext.builder().build())
      }
    }
  }

  @Test
  fun `applyAronaCodecs wires the unsigned provider into MongoClientSettings`() {
    val settings = MongoClientSettings.builder()
      .applyAronaCodecs()
      .build()
    assertEquals(
      UInt::class.java,
      settings.codecRegistry.get(UInt::class.java).encoderClass,
      "applyAronaCodecs() 必须暴露 UInt codec, 否则线上构造的 MongoClient 会再次回到崩溃路径",
    )
  }

  @Test
  fun `UByte decode rejects out-of-range value`() {
    val codec = registry.get(UByte::class.java)
    val doc = BsonDocument("v", BsonInt32(300))
    assertFailsWith<CodecConfigurationException> {
      BsonDocumentReader(doc).use { r ->
        r.readStartDocument()
        r.readName()
        codec.decode(r, DecoderContext.builder().build())
      }
    }
  }
}
