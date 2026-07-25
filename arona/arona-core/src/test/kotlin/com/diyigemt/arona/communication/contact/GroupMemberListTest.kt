package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentEndpoint
import com.diyigemt.arona.communication.message.TencentGroupMemberListResp
import com.diyigemt.arona.communication.message.TencentGroupMemberRaw
import io.ktor.http.HttpMethod
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 群成员列表是文档新接口, wire 细节 (query 名/next_index 语义) 待 sandbox 实测.
// 路由层用 StubBot 钉 endpoint/占位符/query; 聚合器是纯编排函数, 直接喂 fetchPage lambda
// 穷举游标边界 —— 完整性契约: 只有服务端明确说"到底了"才算成功, 截断/漂移一律 failure.
class GroupMemberListTest {

  private val logger = KtorSimpleLogger("GroupMemberListTest")

  private fun page(vararg ids: String, nextIndex: Int? = null) = TencentGroupMemberListResp(
    members = ids.map(::TencentGroupMemberRaw),
    nextIndex = nextIndex,
  )

  @Test
  fun `fetchMemberPage 走 GET 且携带 limit 与 start_index`() {
    val bot = StubBot(callOpenapiResult = Result.success(page("m-1", nextIndex = 12)))
    try {
      val result = runBlocking {
        // limit 故意取 > 400: 服务端额度未证实, "不做本地上限硬校验"是有意契约, 在此钉住 ——
        // 未来若有人加回 1..400 require, 这里先爆炸.
        GroupImpl(bot, bot.coroutineContext, "group-1").fetchMemberPage(limit = 999, startIndex = 7)
      }

      assertEquals(listOf("m-1"), result.getOrThrow().members.map { it.memberOpenid })
      val call = bot.calls.single()
      assertEquals(TencentEndpoint.GetGroupMemberList, call.endpoint)
      assertEquals("group-1", call.placeholders["group_openid"])
      assertEquals(HttpMethod.Get, call.request.method)
      assertEquals("999", call.request.url.parameters["limit"])
      assertEquals("7", call.request.url.parameters["start_index"])
    } finally {
      bot.close()
    }
  }

  @Test
  fun `fetchAllMemberOpenIds 单页终止且不回填成员缓存`() {
    val bot = StubBot(callOpenapiResult = Result.success(page("m-1", "m-2")))
    try {
      val group = GroupImpl(bot, bot.coroutineContext, "group-1")
      val result = runBlocking { group.fetchAllMemberOpenIds() }

      assertEquals(listOf("m-1", "m-2"), result.getOrThrow())
      assertEquals(1, bot.attempts)
      assertTrue(group.members.isEmpty(), "fetchAllMemberOpenIds 不应预热 members 缓存")
    } finally {
      bot.close()
    }
  }

  @Test
  fun `聚合器推进游标并跨页去重`() {
    val starts = mutableListOf<Int>()
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "multi-page") { start ->
        starts += start
        when (start) {
          0 -> Result.success(page("m-1", "m-2", nextIndex = 2))
          2 -> Result.success(page("m-2", "m-3", nextIndex = 0))
          else -> error("unexpected start=$start")
        }
      }
    }

    assertEquals(listOf(0, 2), starts)
    assertEquals(listOf("m-1", "m-2", "m-3"), result.getOrThrow())
  }

  @Test
  fun `聚合器在游标不推进时返回 failure`() {
    val starts = mutableListOf<Int>()
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "stalled") { start ->
        starts += start
        when (start) {
          0 -> Result.success(page("m-1", nextIndex = 5))
          5 -> Result.success(page("m-2", nextIndex = 5))
          else -> error("unexpected start=$start")
        }
      }
    }

    assertEquals(listOf(0, 5), starts)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("did not advance"))
  }

  @Test
  fun `聚合器透传页失败`() {
    val failure = IllegalStateException("page failed")
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "failure") {
        Result.failure(failure)
      }
    }

    assertSame(failure, result.exceptionOrNull())
  }

  @Test
  fun `聚合器透传 fetchPage 直接抛出的异常`() {
    val failure = IllegalStateException("thrown, not wrapped")
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "thrown") {
        throw failure
      }
    }

    assertSame(failure, result.exceptionOrNull())
  }

  @Test
  fun `聚合器对直接抛出的取消异常直通不包装`() {
    val cancellation = CancellationException("cancelled while fetching")
    val thrown = runBlocking {
      assertFailsWith<CancellationException> {
        aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "ce-thrown") {
          throw cancellation
        }
      }
    }
    assertSame(cancellation, thrown)
  }

  @Test
  fun `聚合器把 Result_failure 形态的取消异常还原为抛出`() {
    // 生产 callOpenapi 用 runCatching 包 HTTP, 协程取消到达聚合器的真实形态是 Result.failure(CE);
    // 不还原会把取消降格为普通业务失败, 破坏协作式取消传播.
    val cancellation = CancellationException("cancelled inside callOpenapi")
    val thrown = runBlocking {
      assertFailsWith<CancellationException> {
        aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "ce-wrapped") {
          Result.failure(cancellation)
        }
      }
    }
    assertSame(cancellation, thrown)
  }

  @Test
  fun `聚合器翻满 maxPages 仍有后续游标时返回 failure 而非截断成功`() {
    var attempts = 0
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 2, logger = logger, label = "capped") { start ->
        attempts += 1
        Result.success(page("m-$start", nextIndex = start + 1))
      }
    }

    assertEquals(2, attempts)
    assertTrue(result.exceptionOrNull()!!.message!!.contains("maxPages=2"))
  }

  @Test
  fun `聚合器把空页视作完整结束`() {
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "empty") {
        Result.success(page())
      }
    }

    assertEquals(emptyList(), result.getOrThrow())
  }

  @Test
  fun `聚合器把缺 openid 的非空页判为 schema 漂移 failure`() {
    val result = runBlocking {
      aggregateGroupMemberPages(maxPages = 5, logger = logger, label = "schema-drift") {
        Result.success(page(""))
      }
    }

    assertTrue(result.exceptionOrNull()!!.message!!.contains("blank member_openid"))
  }

  @Test
  fun `DTO 容错 - 未映射的 join_timestamp 被忽略且缺字段走默认值`() {
    // join_timestamp 的 wire 类型未证实, 刻意不映射; 依赖生产 bot.json 的 ignoreUnknownKeys
    // 跳过它 —— 即便是字符串形态也不会让整页解码失败.
    val tolerantJson = Json { ignoreUnknownKeys = true }
    val decoded = tolerantJson.decodeFromString<TencentGroupMemberListResp>(
      """
        {
          "members": [
            {"member_openid": "m-1", "join_timestamp": "unverified-string-shape"}
          ],
          "next_index": 0
        }
      """.trimIndent()
    )
    assertEquals("m-1", decoded.members.single().memberOpenid)
    assertEquals(0, decoded.nextIndex)

    val defaults = tolerantJson.decodeFromString<TencentGroupMemberListResp>("{}")
    assertEquals(emptyList(), defaults.members)
    assertNull(defaults.nextIndex)
  }
}
