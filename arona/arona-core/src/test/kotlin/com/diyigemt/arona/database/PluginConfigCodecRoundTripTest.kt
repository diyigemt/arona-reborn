package com.diyigemt.arona.database

import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Updates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守护插件配置 `JsonObject` 叶子的 Mongo codec 形态:
 *
 *  1. `KotlinSerializerCodec` 遇到嵌套 `JsonObject` 字段时按 registry 顺序委派给
 *     [KotlinxJsonElementCodecProvider], 而不是套用多态序列化策略生成 `__type` / `value`
 *     形态的 BSON.
 *
 *  2. 通过 driver builder API 写出的 `Updates.set(path, JsonObject)` 在序列化阶段也会经过同一
 *     条 codec 链, 落到 BSON 时是 `BsonDocument` 而非 `BsonString`. 这是命令侧 / endpoint 写
 *     路径产出 BSON 形状的最终断言.
 */
class PluginConfigCodecRoundTripTest {

  // 必须用 applyAronaCodecs() 装出的完整 registry, 直接 fromProviders(KotlinxJsonElementCodecProvider)
  // 不能验证嵌套场景: `KotlinSerializerCodec` 在前, 才能复现生产 codec 查找顺序.
  private val codecRegistry: CodecRegistry = MongoClientSettings.builder()
    .applyAronaCodecs()
    .build()
    .codecRegistry

  @Test
  fun `Map of JsonObject leaves round-trips with KotlinSerializerCodec delegating to JsonElementCodec`() {
    val original = JsonObjectConfigDocument(
      config = mapOf(
        "pluginNs" to mapOf(
          "main" to samplePluginConfig(),
        ),
      ),
    )

    val encoded = encodeDocument(original, JsonObjectConfigDocument::class.java)
    val leaf = encoded["config"]!!.asDocument()["pluginNs"]!!.asDocument()["main"]!!

    assertTrue(leaf.isDocument, "JsonObject 叶子必须落成 BSON Document")
    assertFalse(
      leaf.asDocument().containsKey("__type"),
      "若出现 __type 字段说明走了多态序列化, 计划需重审",
    )
    assertEquals(original, decodeDocument(encoded, JsonObjectConfigDocument::class.java))
  }

  @Test
  fun `Updates set with JsonObject value serializes leaf as BSON document not string`() {
    val update = Updates.set("config.pluginNs.main", samplePluginConfig())
      .toBsonDocument(BsonDocument::class.java, codecRegistry)

    val leaf = update["\$set"]!!.asDocument()["config.pluginNs.main"]!!

    assertTrue(
      leaf.isDocument,
      "Updates.set 的 JsonObject 值在 driver 序列化后必须是 BSON Document, 命令侧/endpoint 写出的 \$set 才能是结构化数据",
    )
    assertFalse(leaf.asDocument().containsKey("__type"))
  }

  private fun samplePluginConfig(): JsonObject = buildJsonObject {
    put("enabled", JsonPrimitive(true))
    put("limit", JsonPrimitive(7))
    put("label", JsonPrimitive("default"))
    put(
      "nested",
      buildJsonObject {
        put("threshold", JsonPrimitive(12L))
      },
    )
  }

  private fun <T : Any> encodeDocument(value: T, clazz: Class<T>): BsonDocument {
    val codec = codecRegistry.get(clazz)
    val doc = BsonDocument()
    BsonDocumentWriter(doc).use { writer ->
      codec.encode(writer, value, EncoderContext.builder().build())
    }
    return doc
  }

  private fun <T : Any> decodeDocument(doc: BsonDocument, clazz: Class<T>): T {
    val codec = codecRegistry.get(clazz)
    return BsonDocumentReader(doc).use { reader ->
      codec.decode(reader, DecoderContext.builder().build())
    }
  }

  @Serializable
  private data class JsonObjectConfigDocument(
    val config: Map<String, Map<String, JsonObject>>,
  )
}
