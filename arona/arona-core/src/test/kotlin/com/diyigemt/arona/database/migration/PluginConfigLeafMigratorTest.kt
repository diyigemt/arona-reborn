package com.diyigemt.arona.database.migration

import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonNull
import org.bson.BsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 固化迁移器纯函数 [migrateDocument] 行为. 本项目无 embedded Mongo, runOnceIfNeeded 整段无法跑;
 * 这里仅证 transform 正确, 实集成验证留给 staging.
 */
class PluginConfigLeafMigratorTest {

  @Test
  fun `legacy BsonString leaf is parsed into BsonDocument`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse(
      """{"_id":"u1","config":{"ns":{"main":"{\"enabled\":true,\"limit\":7}"}}}""",
    )

    val migrated = assertNotNull(migrateDocument(doc, includeMembers = false, stats = stats))
    val leaf = migrated["config"]!!.asDocument()["ns"]!!.asDocument()["main"]!!

    assertTrue(leaf.isDocument, "leaf 必须由 BsonString 升级为 BsonDocument")
    assertEquals(true, leaf.asDocument()["enabled"]!!.asBoolean().value)
    assertEquals(1, stats.scanned)
    assertEquals(1, stats.converted)
    assertEquals(0, stats.failedLeaves)
    assertEquals(0, stats.unexpectedLeafTypes)
  }

  @Test
  fun `BsonDocument leaf is kept and migration is idempotent`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse(
      """{"_id":"u1","config":{"ns":{"main":{"enabled":true}}}}""",
    )

    assertNull(migrateDocument(doc, includeMembers = false, stats = stats), "已迁移过的文档不需要写回")
    assertEquals(1, stats.scanned)
    assertEquals(0, stats.converted)
  }

  @Test
  fun `document without config returns null and is only counted once`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse("""{"_id":"u1","username":"teacher"}""")

    assertNull(migrateDocument(doc, includeMembers = false, stats = stats))
    assertEquals(1, stats.scanned)
  }

  @Test
  fun `invalid legacy JSON leaf throws fail-fast with id and path`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse(
      """{"_id":"u1","config":{"ns":{"main":"not-valid-json"}}}""",
    )

    val ex = assertFailsWith<IllegalStateException> {
      migrateDocument(doc, includeMembers = false, stats = stats)
    }

    assertTrue(ex.message!!.contains("_id=u1"), "异常信息要带 doc _id: ${ex.message}")
    assertTrue(ex.message!!.contains("config.ns.main"), "异常信息要带 leaf path: ${ex.message}")
    assertEquals(1, stats.failedLeaves)
  }

  /**
   * 防御 mongo java driver `BsonDocument.parse` 的 Extended JSON 行为: 旧版迁移用它解析叶子时,
   * 嵌套 `{"$date":"..."}` 会被识别成 BsonDateTime 等非 JSON 兼容类型, 之后 KotlinxJsonElementCodecProvider
   * 读路径直接拒解, 服务崩.
   *
   * 切到 kotlinx 解析 + [PluginWebuiConfigRecorder.requireSafeBsonLeafKeys] 后, `$date` 作为字段名
   * 应当被 leaf-key 校验拦下来, 不再有 Extended JSON 误解析的机会.
   */
  @Test
  fun `legacy leaf containing Extended JSON marker field is rejected without BSON coercion`() {
    val stats = MigrationStats()
    val doc = legacyLeafDoc(id = "u1", raw = """{"someField":{"${'$'}date":"2024-01-01T00:00:00Z"}}""")

    val ex = assertFailsWith<IllegalStateException> {
      migrateDocument(doc, includeMembers = false, stats = stats)
    }

    assertTrue(ex.message!!.contains("_id=u1"), "异常信息要带 doc _id: ${ex.message}")
    assertTrue(ex.message!!.contains("config.ns.main"), "异常信息要带 leaf path: ${ex.message}")
    assertTrue(ex.message!!.contains("starts with forbidden character"), "异常信息要带 unsafe key 原因: ${ex.message}")
    assertEquals(1, stats.failedLeaves)
  }

  /** leaf 字段名以 `$` 开头, 命中 [PluginWebuiConfigRecorder.requireSafeBsonLeafKeys]. */
  @Test
  fun `legacy leaf with dollar-prefixed key throws fail-fast`() {
    val stats = MigrationStats()
    val doc = legacyLeafDoc(id = "u1", raw = """{"${'$'}weird":1}""")

    val ex = assertFailsWith<IllegalStateException> {
      migrateDocument(doc, includeMembers = false, stats = stats)
    }

    assertTrue(ex.message!!.contains("_id=u1"))
    assertTrue(ex.message!!.contains("config.ns.main"))
    assertTrue(ex.message!!.contains("starts with forbidden character"), ex.message)
    assertEquals(1, stats.failedLeaves)
  }

  /** leaf 字段名含 `.`, 命中 [PluginWebuiConfigRecorder.requireSafeBsonLeafKeys]. */
  @Test
  fun `legacy leaf with dotted key throws fail-fast`() {
    val stats = MigrationStats()
    val doc = legacyLeafDoc(id = "u1", raw = """{"a.b":1}""")

    val ex = assertFailsWith<IllegalStateException> {
      migrateDocument(doc, includeMembers = false, stats = stats)
    }

    assertTrue(ex.message!!.contains("_id=u1"))
    assertTrue(ex.message!!.contains("config.ns.main"))
    assertTrue(ex.message!!.contains("contains forbidden character '.'"), ex.message)
    assertEquals(1, stats.failedLeaves)
  }

  private fun legacyLeafDoc(id: String, raw: String): BsonDocument = BsonDocument()
    .append("_id", BsonString(id))
    .append(
      "config",
      BsonDocument().append("ns", BsonDocument().append("main", BsonString(raw))),
    )

  @Test
  fun `contact members nested config is migrated through array recursion`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse(
      """
      {
        "_id": "c1",
        "members": [
          {
            "_id": "u1",
            "config": {
              "ns": {
                "member": "{\"enabled\":true}"
              }
            }
          }
        ]
      }
      """.trimIndent(),
    )

    val migrated = assertNotNull(migrateDocument(doc, includeMembers = true, stats = stats))
    val member = migrated["members"]!!.asArray()[0].asDocument()
    val leaf = member["config"]!!.asDocument()["ns"]!!.asDocument()["member"]!!

    assertTrue(leaf.isDocument, "member.config leaf 必须升级")
    assertEquals(1, stats.scanned, "scanned 仅在顶层 contact 文档计 1, members 内部不重复加")
    assertEquals(1, stats.converted)
  }

  @Test
  fun `includeMembers true tolerates documents without a members field`() {
    val stats = MigrationStats()
    val doc = BsonDocument.parse("""{"_id":"c1","config":{}}""")

    assertNull(migrateDocument(doc, includeMembers = true, stats = stats))
    assertEquals(1, stats.scanned)
  }

  @Test
  fun `BsonNull leaf counts as unexpected and is preserved`() {
    val stats = MigrationStats()
    val doc = BsonDocument()
      .append("_id", BsonString("u1"))
      .append(
        "config",
        BsonDocument()
          .append("ns", BsonDocument().append("main", BsonNull.VALUE)),
      )

    assertNull(migrateDocument(doc, includeMembers = false, stats = stats), "全是 unexpected 不算 dirty, 不需要写回")
    assertEquals(0, stats.converted)
    assertEquals(0, stats.failedLeaves)
    assertEquals(1, stats.unexpectedLeafTypes)
  }

  @Test
  fun `non-document members entries are silently ignored`() {
    val stats = MigrationStats()
    val doc = BsonDocument()
      .append("_id", BsonString("c1"))
      .append("members", BsonArray(listOf(BsonInt32(1))))

    assertNull(migrateDocument(doc, includeMembers = true, stats = stats))
    assertEquals(1, stats.scanned)
  }

  @Test
  fun `mixed leaves accumulate stats accurately across two docs`() {
    val stats = MigrationStats()
    // u1: 一个有效 BsonString + 一个非文档 namespace (会算 unexpected)
    val u1 = BsonDocument.parse(
      """{"_id":"u1","config":{"ns":{"main":"{\"enabled\":true}"},"badns":1}}""",
    )
    val u2 = BsonDocument.parse(
      """{"_id":"u2","config":{"ns":{"main":"not-json"}}}""",
    )

    assertNotNull(migrateDocument(u1, includeMembers = false, stats = stats))
    assertFailsWith<IllegalStateException> {
      migrateDocument(u2, includeMembers = false, stats = stats)
    }

    assertEquals(2, stats.scanned)
    assertEquals(1, stats.converted)
    assertEquals(1, stats.failedLeaves)
    assertEquals(1, stats.unexpectedLeafTypes, "u1 的 'badns:1' namespace 算 unexpected")
  }
}
