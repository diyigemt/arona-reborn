package com.diyigemt.arona.database

import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Updates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
 * 预飞测试: 把插件配置叶子从 `String` 改成 `JsonObject` 前, 必须先证实以下前置假设:
 *
 *  1. `KotlinSerializerCodec` 在遇到嵌套 `JsonObject` / `JsonElement` 字段时, 会按 registry
 *     顺序委派给 [KotlinxJsonElementCodecProvider], 而不是套用多态序列化策略生成 `__type` /
 *     `value` 形态的 BSON. 一旦出现 `__type`, 整个迁移方案需要重审.
 *
 *  2. 通过 driver builder API 写出的 `Updates.set(path, JsonObject)` 在序列化阶段也会经过同一
 *     条 codec 链, 落到 BSON 时是 `BsonDocument` 而非 `BsonString`. 这是命令侧 / endpoint 写
 *     路径产出 BSON 形状的最终断言.
 *
 *  3. 当 `JsonObject` 内部字段名含 `.` 或前导 `$` 时, [KotlinxJsonElementCodecProvider] 当前
 *     的行为是**原样写入字段名**, 不做拦截. 这条测试不是为了"验证它做了正确的事", 而是为了
 *     **固化现实行为**: 这意味着批 2 必须在 `preparePluginConfigWrite` 里加一道 leaf-key 安全
 *     扫描, 否则用户配置里出现的危险字段名会一路落到 Mongo.
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
  fun `Map of JsonElement leaves round-trips through the same codec chain`() {
    val original = JsonElementConfigDocument(
      config = mapOf(
        "pluginNs" to mapOf(
          "main" to samplePluginConfig(),
        ),
      ),
    )

    val encoded = encodeDocument(original, JsonElementConfigDocument::class.java)
    val leaf = encoded["config"]!!.asDocument()["pluginNs"]!!.asDocument()["main"]!!

    assertTrue(leaf.isDocument, "JsonElement (实际为 JsonObject) 叶子必须落成 BSON Document")
    assertFalse(
      leaf.asDocument().containsKey("__type"),
      "若出现 __type 字段说明走了多态序列化, 计划需重审",
    )
    assertEquals(original, decodeDocument(encoded, JsonElementConfigDocument::class.java))
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

  @Test
  fun `JsonElementCodec passes dot and dollar field names through without rejection`() {
    val original = LeafKeyShapeDocument(
      payload = buildJsonObject {
        put("a.b", JsonPrimitive(1))
        put("\$set", JsonPrimitive("literal"))
        put("safe", JsonPrimitive(true))
      },
    )

    val encoded = encodeDocument(original, LeafKeyShapeDocument::class.java)
    val payload = encoded["payload"]!!.asDocument()

    assertTrue(payload.containsKey("a.b"), "codec 当前不拦截含点字段名 — prepare 层必须自补一道校验")
    assertTrue(payload.containsKey("\$set"), "codec 当前不拦截 \$ 前缀字段名 — 同上")
    assertTrue(payload.containsKey("safe"))
    assertEquals(original, decodeDocument(encoded, LeafKeyShapeDocument::class.java))
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

  @Serializable
  private data class JsonElementConfigDocument(
    val config: Map<String, Map<String, JsonElement>>,
  )

  @Serializable
  private data class LeafKeyShapeDocument(
    val payload: JsonObject,
  )
}
