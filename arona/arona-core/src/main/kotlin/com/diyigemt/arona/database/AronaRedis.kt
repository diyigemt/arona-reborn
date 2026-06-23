package com.diyigemt.arona.database

import io.github.crackthecodeabhi.kreds.args.SetOption
import io.github.crackthecodeabhi.kreds.args.ZAddGTOrLT
import io.github.crackthecodeabhi.kreds.connection.KredsClient
import io.github.crackthecodeabhi.kreds.pipeline.Pipeline

/**
 * 框架统一的 Redis 操作门面 —— [DatabaseProvider.redisDbQuery] 的 receiver 类型。
 *
 * 全代码库只通过本接口访问 Redis, **不直接依赖任何具体客户端类型**。这样做有两个目的:
 *  1. 把底层 Redis 客户端收敛到唯一一处实现, 调用点与客户端解耦; 将来更换/升级客户端时, 调用点零改动。
 *  2. 线格式 (raw String / 扁平 hash / SCAN 选项编码等) 的取舍集中在实现里一处把关, 杜绝散落各处的误用。
 *
 * 方法集刻意收窄到代码库**实际用到**的操作, 签名只用 JDK/Kotlin 类型。新增 Redis 用法时在此补方法,
 * 而非在调用点直接触碰客户端。
 *
 * 当前实现为 [KredsAronaRedis] (底层 kreds), 行为与历史一致; 后续可整体替换实现而不动调用点。
 */
interface AronaRedis {
  /** GET key。 */
  suspend fun get(key: String): String?

  /** SET key value (无过期), 返回值忽略。 */
  suspend fun set(key: String, value: String)

  /** SET key value EX [ttlSeconds] —— 带过期的写入 (短 id 缓存等)。 */
  suspend fun setEx(key: String, value: String, ttlSeconds: Long)

  /**
   * SET key value EX [ttlSeconds] NX —— 一次往返完成 "不存在才写 + 设过期"。
   * 返回 `true` 表示本次成功占用 (NX 命中), `false` 表示 key 已存在 (用于幂等/分布式锁)。
   */
  suspend fun setNxEx(key: String, value: String, ttlSeconds: Long): Boolean

  /** DEL key..., 返回实际删除的 key 数。 */
  suspend fun del(vararg keys: String): Long

  /** EXPIRE key [ttlSeconds], 返回是否成功设置过期 (key 不存在时为 false)。 */
  suspend fun expire(key: String, ttlSeconds: Long): Boolean

  /** INCR key, 返回自增后的值。 */
  suspend fun incr(key: String): Long

  /** HINCRBY key field [increment], 返回该字段自增后的值。 */
  suspend fun hIncrBy(key: String, field: String, increment: Long): Long

  /** HSET key field value...(至少一对), 返回新建字段数。 */
  suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>): Long

  /**
   * HGETALL key, 统一返回 field -> value 的 [Map] (吸收底层「扁平交替列表 / Map」的形态差异)。
   * **保留服务端返回顺序** (以保序 Map 承载), 因此 `keys.first()` 等顺序敏感用法有确定语义; 实现须维持此约定。
   */
  suspend fun hGetAll(key: String): Map<String, String>

  /** HLEN key, 返回字段数。 */
  suspend fun hLen(key: String): Long

  /** ZADD key GT score member —— 仅当新分数更大时更新, 返回值忽略。 */
  suspend fun zAddGt(key: String, score: Double, member: String)

  /**
   * ZRANGE key [start] [stop] REV [WITHSCORES] —— 按下标的倒序范围查询。
   * [start]/[stop] 为闭区间下标; [withScores] 为 true 时返回**扁平**的 `[member, score, member, score, ...]`。
   */
  suspend fun zRangeRev(key: String, start: Long, stop: Long, withScores: Boolean): List<String>

  /** ZSCORE key member, 返回成员分数 (不存在为 null)。 */
  suspend fun zScore(key: String, member: String): Double?

  /** ZREVRANK key member, 返回成员在倒序中的排名 (不存在为 null)。 */
  suspend fun zRevRank(key: String, member: String): Long?

  /**
   * SCAN [cursor] [MATCH match] [COUNT count] 的单批迭代。
   * 调用方据返回的 [ScanPage.cursor] 续扫 (为 "0" 即结束)。[match]/[count] 为 null 时不下发对应选项。
   */
  suspend fun scan(cursor: String, match: String?, count: Long?): ScanPage

  /**
   * 把 [block] 内的写命令打包成一次 pipeline 发送 (忽略各命令返回值)。
   * 仅暴露当前用到的写操作, 不返回结果; 需要读结果的批量请逐条调用本门面的读方法。
   */
  suspend fun pipeline(block: suspend RedisPipeline.() -> Unit)
}

/** [AronaRedis.pipeline] 内可用的写命令集 (fire-and-forget, 结果统一在批末提交)。 */
interface RedisPipeline {
  suspend fun incr(key: String)
  suspend fun hIncrBy(key: String, field: String, increment: Long)
  suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>)
}

/** 一次 SCAN 批的结果: [cursor] 为下一轮游标 ("0" 表示结束), [keys] 为本批返回的键。 */
data class ScanPage(
  val cursor: String,
  val keys: List<String>,
)

/**
 * [AronaRedis] 的 kreds 0.9.1 实现。逐方法**等价封装**现有 kreds 调用, 不改变任何线格式与行为;
 * 仅把客户端差异 (hgetAll 扁平列表、zscore 返回字符串、hset 首参必填、Pipeline 句柄等) 收敛在此处。
 */
internal class KredsAronaRedis(
  private val client: KredsClient,
) : AronaRedis {

  override suspend fun get(key: String): String? = client.get(key)

  override suspend fun set(key: String, value: String) {
    client.set(key, value)
  }

  override suspend fun setEx(key: String, value: String, ttlSeconds: Long) {
    client.set(key, value, SetOption.Builder(exSeconds = ttlSeconds.toULong()).build())
  }

  override suspend fun setNxEx(key: String, value: String, ttlSeconds: Long): Boolean =
    client.set(key, value, SetOption.Builder(nx = true, exSeconds = ttlSeconds.toULong()).build()) == "OK"

  override suspend fun del(vararg keys: String): Long = client.del(*keys)

  override suspend fun expire(key: String, ttlSeconds: Long): Boolean =
    client.expire(key, ttlSeconds.toULong()) == 1L

  override suspend fun incr(key: String): Long = client.incr(key)

  override suspend fun hIncrBy(key: String, field: String, increment: Long): Long =
    client.hincrBy(key, field, increment)

  override suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>): Long {
    require(fieldValues.isNotEmpty()) { "HSET 至少需要一对 field/value" }
    // kreds 0.9.1 的 hset 首个 field/value 对是必选参数, 其余才是 vararg, 不能直接 hset(key, *fieldValues)。
    return client.hset(key, fieldValues.first(), *fieldValues.copyOfRange(1, fieldValues.size))
  }

  override suspend fun hGetAll(key: String): Map<String, String> {
    // kreds hgetAll 返回 [field0, value0, field1, value1, ...] 扁平交替列表, 在此统一成 Map。
    // 奇数长度意味着回复错位 (如连接回复队列被孤儿回复污染), 直接抛错暴露问题而非静默丢字段。
    val flat = client.hgetAll(key)
    require(flat.size % 2 == 0) { "redis hash response has odd element count: ${flat.size}" }
    return flat.chunked(2).associate { (field, value) -> field to value }
  }

  override suspend fun hLen(key: String): Long = client.hlen(key)

  override suspend fun zAddGt(key: String, score: Double, member: String) {
    // kreds 0.9.1 的 scoreMember 分数为 Int; 这些分数本就是整数值, 转 Int 保持现有线格式。
    client.zadd(key, gtOrLt = ZAddGTOrLT.GT, scoreMember = score.toInt() to member)
  }

  override suspend fun zRangeRev(key: String, start: Long, stop: Long, withScores: Boolean): List<String> =
    client.zrange(key, start, stop, by = null, rev = true, limit = null, withScores = withScores)

  override suspend fun zScore(key: String, member: String): Double? =
    // kreds 0.9.1 的 zscore 返回 String?, 门面统一为 Double?。
    client.zscore(key, member)?.toDouble()

  override suspend fun zRevRank(key: String, member: String): Long? = client.zrevrank(key, member)

  override suspend fun scan(cursor: String, match: String?, count: Long?): ScanPage {
    // PR1 维持 kreds 0.9.1 的无选项 plain SCAN (其 MATCH/COUNT 编码有缺陷); match/count 有意不下发,
    // 由调用方的客户端侧过滤兜底。切换到能正确编码 SCAN 选项的客户端后再恢复 match/count。
    val page = client.scan(cursor.toLong())
    return ScanPage(cursor = page.cursor.toString(), keys = page.elements)
  }

  override suspend fun pipeline(block: suspend RedisPipeline.() -> Unit) {
    val pipeline = client.pipelined()
    KredsRedisPipeline(pipeline).block()
    pipeline.execute()
  }

  /** 把门面 pipeline 写命令委托到 kreds [Pipeline]; 各命令返回 Response 句柄, 由外层 [Pipeline.execute] 统一提交。 */
  private class KredsRedisPipeline(
    private val delegate: Pipeline,
  ) : RedisPipeline {
    override suspend fun incr(key: String) {
      delegate.incr(key)
    }

    override suspend fun hIncrBy(key: String, field: String, increment: Long) {
      delegate.hincrBy(key, field, increment)
    }

    override suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>) {
      require(fieldValues.isNotEmpty()) { "HSET 至少需要一对 field/value" }
      delegate.hset(key, fieldValues.first(), *fieldValues.copyOfRange(1, fieldValues.size))
    }
  }
}
