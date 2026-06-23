package com.diyigemt.arona.database

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.types.common.RespVer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * re.this 门面 ([ReThisAronaRedis]) 的**真实 Redis 线格式**验收测试 —— 这是 kreds→re.this 迁移 (PR2) 的关键门槛:
 * 不仅验证命令语义, 更验证「不经 serde、值不被 JSON 加引号」的线格式与历史 (kreds) 完全一致, 且 RESP2 下
 * WITHSCORES 维持扁平交替数组 (RankManager 的 `windowed(2,2)` 解析依赖此形态)、SCAN MATCH/COUNT 不再触发
 * kreds 0.9.1 时代的 `ERR syntax error`。
 *
 * 需要本地 Redis (127.0.0.1:6379)。不可达时整类用 [assumeTrue] 优雅跳过 (CI 无 Redis 不算失败); 测试在独立的
 * db 15 上、用专属 key 前缀, 并在 [teardown] 中 SCAN+DEL 清理, 不污染其它库/数据, 绝不 FLUSHDB。
 */
class ReThisWireFormatTest {
  private val host = "127.0.0.1"
  private val port = 6379
  private val testDb = 15
  private val prefix = "arona-rethis-test:"

  private lateinit var client: ReThis
  private lateinit var redis: AronaRedis

  private fun redisReachable(): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(host, port), 300) }
    true
  }.getOrDefault(false)

  @BeforeTest
  fun setup() {
    assumeTrue("Redis ($host:$port) 不可达, 跳过 re.this 线格式集成测试", redisReachable())
    client = ReThis(host = host, port = port, protocol = RespVer.V2) {
      db = testDb
      usePooling = true
      maxConnections = 4
    }
    redis = ReThisAronaRedis(client)
  }

  @AfterTest
  fun teardown() {
    if (!::client.isInitialized) return
    runBlocking {
      runCatching {
        var cursor = "0"
        do {
          val page = redis.scan(cursor, match = "$prefix*", count = 200)
          if (page.keys.isNotEmpty()) redis.del(*page.keys.toTypedArray())
          cursor = page.cursor
        } while (cursor != "0")
      }
    }
    client.close()
  }

  @Test
  fun `setNxEx 首次成功 重复失败 且存储为裸字节串而非 JSON 引号`() = runBlocking {
    val key = "${prefix}nx"
    assertTrue(redis.setNxEx(key, "1", 60), "首次 SET NX 应成功占用")
    assertFalse(redis.setNxEx(key, "1", 60), "key 已存在, 重复 SET NX 应失败")
    // 若误用 serde 层, 值会被 JSON 序列化成 "\"1\""; 原始层应原样写入裸 1。
    assertEquals("1", redis.get(key), "存储值应为裸字节串 1, 不带 JSON 引号")
  }

  @Test
  fun `setEx 写入带 TTL expire 对存在与否分别返回 true false`() = runBlocking {
    val key = "${prefix}ttl"
    redis.setEx(key, "v", 100)
    assertEquals("v", redis.get(key), "setEx 值应原样取回")
    assertTrue(redis.expire(key, 50), "对已存在 key 设置过期应返回 true")
    assertFalse(redis.expire("${prefix}absent", 50), "对不存在 key 设置过期应返回 false")
  }

  @Test
  fun `hSet 原始写入 hGetAll 取回原值且保序`() = runBlocking {
    val key = "${prefix}hash"
    redis.hSet(key, "b" to "2", "a" to "1", "c" to "3")
    val all = redis.hGetAll(key)
    assertEquals(mapOf("b" to "2", "a" to "1", "c" to "3"), all, "字段值应原样取回, 不带引号")
    // 小 hash (listpack 编码) HGETALL 按插入顺序返回; 门面以 LinkedHashMap 保序, 支撑 keys.first() 等顺序敏感用法。
    assertEquals(listOf("b", "a", "c"), all.keys.toList(), "应保留服务端返回顺序")
    assertEquals(3L, redis.hLen(key), "hLen 应为字段数")
  }

  @Test
  fun `scan 下发 MATCH 与 COUNT 不再 ERR 且返回匹配键`() = runBlocking {
    val k1 = "${prefix}dau.2026-06-01.message"
    val k2 = "${prefix}dau.2026-06-02.message"
    redis.set(k1, "1")
    redis.set(k2, "2")
    val found = mutableSetOf<String>()
    var cursor = "0"
    do {
      val page = redis.scan(cursor, match = "${prefix}dau.*", count = 100)
      found += page.keys
      cursor = page.cursor
    } while (cursor != "0")
    assertTrue(found.containsAll(listOf(k1, k2)), "SCAN MATCH/COUNT 应返回匹配的 dau.* 键 (不再 ERR syntax error)")
  }

  @Test
  fun `RESP2 下 zRangeRev withScores 返回扁平 member score 交替数组`() = runBlocking {
    val key = "${prefix}zset"
    redis.zAddGt(key, 10.0, "alice")
    redis.zAddGt(key, 30.0, "bob")
    redis.zAddGt(key, 20.0, "carol")
    val flat = redis.zRangeRev(key, 0, -1, withScores = true)
    // RESP2 下应是扁平 [member, score, member, score, ...]; 倒序: bob(30) > carol(20) > alice(10)。
    assertEquals(6, flat.size, "3 成员 * (member+score) 共 6 个扁平元素")
    assertEquals(listOf("bob", "carol", "alice"), listOf(flat[0], flat[2], flat[4]), "倒序成员顺序")
    assertEquals(
      listOf(30.0, 20.0, 10.0),
      listOf(flat[1], flat[3], flat[5]).map { it.toDouble() },
      "扁平数组的奇数位应为对应分数",
    )
  }

  @Test
  fun `zAddGt 仅在新分数更大时更新`() = runBlocking {
    val key = "${prefix}zgt"
    redis.zAddGt(key, 10.0, "m")
    redis.zAddGt(key, 5.0, "m")  // 更小, 不应更新
    assertEquals(10.0, redis.zScore(key, "m"), "GT 语义: 更小的分数不覆盖")
    redis.zAddGt(key, 20.0, "m")  // 更大, 应更新
    assertEquals(20.0, redis.zScore(key, "m"), "GT 语义: 更大的分数更新")
    assertEquals(0L, redis.zRevRank(key, "m"), "唯一成员倒序排名应为 0")
  }

  @Test
  fun `pipeline 批量写入全部生效`() = runBlocking {
    val counter = "${prefix}pipe.counter"
    val hash = "${prefix}pipe.hash"
    val userMap = "${prefix}pipe.user"
    redis.pipeline {
      incr(counter)
      incr(counter)
      hIncrBy(hash, "cmd", 5)
      hSet(userMap, "u1" to "t1")
    }
    assertEquals("2", redis.get(counter), "两次 incr 后计数应为 2")
    assertEquals(mapOf("cmd" to "5"), redis.hGetAll(hash), "pipeline hIncrBy 应生效且值不带引号")
    assertEquals(mapOf("u1" to "t1"), redis.hGetAll(userMap), "pipeline hSet 应生效且值不带引号")
  }

  @Test
  fun `pipeline 内单条命令服务端错误不被静默吞掉`() = runBlocking {
    val key = "${prefix}pipe.wrongtype"
    redis.hSet(key, "f" to "v")  // 此 key 现在是 hash 类型
    // 对 hash 执行 INCR 触发 WRONGTYPE; re.this 把它收进 RType.Error, 门面应显式抛出而非静默"成功"。
    var threw = false
    try {
      redis.pipeline { incr(key) }
    } catch (e: Throwable) {
      threw = true
    }
    assertTrue(threw, "pipeline 内 WRONGTYPE 错误应作为异常抛出, 不被静默吞掉")
  }
}
