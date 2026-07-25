package com.diyigemt.arona.database.permission

import com.diyigemt.arona.database.applyAronaCodecs
import com.mongodb.MongoClientSettings
import kotlinx.serialization.json.Json
import org.bson.BsonBoolean
import org.bson.BsonDocument
import org.bson.BsonDocumentReader
import org.bson.BsonDocumentWriter
import org.bson.BsonInt64
import org.bson.BsonString
import org.bson.codecs.DecoderContext
import org.bson.codecs.EncoderContext
import kotlin.test.Test
import kotlin.test.assertEquals

// 持久化契约钉子: proactiveMessageState / proactiveMessageStateUpdatedAt 是新加字段,
// 存量文档没有它们. 这里用与生产完全相同的 codec registry (applyAronaCodecs) 走真实 BSON
// 编解码, 而不是 JSON 代理 —— 同时钉住 codec 装配本身.
class ProactiveMessageStateCompatTest {

  private val codecRegistry = MongoClientSettings.builder().applyAronaCodecs().build().codecRegistry

  private inline fun <reified T : Any> decode(doc: BsonDocument): T =
    codecRegistry.get(T::class.java).decode(BsonDocumentReader(doc), DecoderContext.builder().build())

  private inline fun <reified T : Any> encode(value: T): BsonDocument {
    val doc = BsonDocument()
    codecRegistry.get(T::class.java).encode(BsonDocumentWriter(doc), value, EncoderContext.builder().build())
    return doc
  }

  @Test
  fun `存量 BSON 文档缺新字段时经生产 codec 解码得 UNKNOWN 与 0`() {
    val contact = decode<MongoContactDocument>(BsonDocument("_id", BsonString("legacy-contact")))
    assertEquals(ProactiveMessageState.UNKNOWN, contact.proactiveMessageState)
    assertEquals(0L, contact.proactiveMessageStateUpdatedAt)

    val user = decode<MongoUserDocument>(BsonDocument("_id", BsonString("legacy-user")))
    assertEquals(ProactiveMessageState.UNKNOWN, user.proactiveMessageState)
    assertEquals(0L, user.proactiveMessageStateUpdatedAt)
  }

  @Test
  fun `生产 codec 对开关状态按枚举 name 字符串编解码`() {
    // updateProactiveMessageState 走 Updates.set(state.name) 写裸字符串, 读路径靠 codec 解回枚举;
    // 两侧的 wire 形态必须一致, 这里从编码侧钉住 "codec 也存 name 字符串".
    val encoded = encode(
      MongoContactDocument(
        id = "c-1",
        proactiveMessageState = ProactiveMessageState.ALLOW,
        proactiveMessageStateUpdatedAt = 42L,
      )
    )
    assertEquals(BsonString("ALLOW"), encoded["proactiveMessageState"])
    assertEquals(BsonInt64(42L), encoded["proactiveMessageStateUpdatedAt"])

    val decoded = decode<MongoContactDocument>(encoded)
    assertEquals(ProactiveMessageState.ALLOW, decoded.proactiveMessageState)
    assertEquals(42L, decoded.proactiveMessageStateUpdatedAt)
  }

  @Test
  fun `枚举 wire 名字面稳定`() {
    // 枚举名即持久化契约: 重命名/换序导致的 wire 漂移必须先在这里爆炸.
    assertEquals(listOf("UNKNOWN", "ALLOW", "REJECT"), ProactiveMessageState.entries.map { it.name })
    assertEquals(
      listOf("\"UNKNOWN\"", "\"ALLOW\"", "\"REJECT\""),
      ProactiveMessageState.entries.map { Json.encodeToString(ProactiveMessageState.serializer(), it) },
    )
  }

  @Test
  fun `ContactDocument mapper round-trip 保留开关字段`() {
    val restored = ContactDocument(
      id = "c-rt",
      proactiveMessageState = ProactiveMessageState.ALLOW,
      proactiveMessageStateUpdatedAt = 123L,
    ).toMongo().toDomain()
    assertEquals(ProactiveMessageState.ALLOW, restored.proactiveMessageState)
    assertEquals(123L, restored.proactiveMessageStateUpdatedAt)
  }

  @Test
  fun `UserDocument mapper round-trip 保留开关字段`() {
    val restored = UserDocument(
      id = "u-rt",
      proactiveMessageState = ProactiveMessageState.REJECT,
      proactiveMessageStateUpdatedAt = 456L,
    ).toMongo().toDomain()
    assertEquals(ProactiveMessageState.REJECT, restored.proactiveMessageState)
    assertEquals(456L, restored.proactiveMessageStateUpdatedAt)
  }

  @Test
  fun `开关字段名在 domain 与 wrapper 四型间一致`() {
    // 两组隐式耦合的显式钉子: ① freshness filter 取 ContactDocument 的属性名但被 User 更新复用;
    // ② updateProactiveMessageState 写侧用 domain 属性名, codec 读侧按 wrapper 属性名解码.
    // 任何一型单独重命名都能编译通过且现有 filter 测试发现不了, 必须先在这里爆炸.
    assertEquals(
      setOf(ContactDocument::proactiveMessageState.name),
      setOf(
        UserDocument::proactiveMessageState.name,
        MongoContactDocument::proactiveMessageState.name,
        MongoUserDocument::proactiveMessageState.name,
      ),
    )
    assertEquals(
      setOf(ContactDocument::proactiveMessageStateUpdatedAt.name),
      setOf(
        UserDocument::proactiveMessageStateUpdatedAt.name,
        MongoContactDocument::proactiveMessageStateUpdatedAt.name,
        MongoUserDocument::proactiveMessageStateUpdatedAt.name,
      ),
    )
  }

  @Test
  fun `乱序防回写 filter 对缺字段存量文档与不晚于事件的时间戳均放行`() {
    // 关键: $lte 不匹配缺字段文档, 没有 $exists:false 分支时存量文档的第一次开关事件会静默丢失.
    // listener 层无 Mongo 集成测试, 该结构是这个语义唯一的回归钉子.
    val rendered = proactiveMessageFreshnessFilter("contact-1", 42L)
      .toBsonDocument(BsonDocument::class.java, codecRegistry)

    val and = rendered["\$and"]!!.asArray()
    assertEquals(2, and.size)
    assertEquals(BsonString("contact-1"), and[0].asDocument()["_id"], "filter 必须保留 _id 谓词")

    val or = and[1].asDocument()["\$or"]!!.asArray()
    assertEquals(2, or.size)
    val field = ContactDocument::proactiveMessageStateUpdatedAt.name
    assertEquals(
      BsonBoolean.FALSE,
      or[0].asDocument()[field]!!.asDocument()["\$exists"],
      "缺字段的存量文档必须可被更新",
    )
    assertEquals(
      BsonInt64(42L),
      or[1].asDocument()[field]!!.asDocument()["\$lte"],
      "已存时间戳不晚于事件的文档必须可被更新",
    )
  }
}
