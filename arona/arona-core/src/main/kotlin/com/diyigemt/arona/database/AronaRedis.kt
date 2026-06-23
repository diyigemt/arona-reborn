package com.diyigemt.arona.database

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.generic.del
import eu.vendeli.rethis.command.generic.expire
import eu.vendeli.rethis.command.generic.scan
import eu.vendeli.rethis.command.hash.hGetAll
import eu.vendeli.rethis.command.hash.hIncrBy
import eu.vendeli.rethis.command.hash.hLen
import eu.vendeli.rethis.command.hash.hSet
import eu.vendeli.rethis.command.sortedset.zAdd
import eu.vendeli.rethis.command.sortedset.zRange
import eu.vendeli.rethis.command.sortedset.zRevRank
import eu.vendeli.rethis.command.sortedset.zScore
import eu.vendeli.rethis.command.string.get
import eu.vendeli.rethis.command.string.incr
import eu.vendeli.rethis.command.string.set
import eu.vendeli.rethis.shared.request.common.FieldValue
import eu.vendeli.rethis.shared.request.common.UpdateStrategyOption
import eu.vendeli.rethis.shared.request.generic.ScanOption
import eu.vendeli.rethis.shared.request.string.SetExpire
import eu.vendeli.rethis.shared.request.string.UpsertMode
import eu.vendeli.rethis.shared.response.sortedset.ZMember
import eu.vendeli.rethis.shared.types.RType
import kotlin.time.Duration.Companion.seconds

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
 * 当前实现为 [ReThisAronaRedis] (底层 re.this 池化客户端), 只调用 re.this 的原始命令层 (不经 serde)。
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
 * [AronaRedis] 的 re.this 0.4.3 实现。**只调用原始命令层** (`eu.vendeli.rethis.command.{string,hash,generic,sortedset}`
 * 的扩展函数), 绝不触碰 serde 层 —— serde 会把 String 值 JSON 序列化 (额外加引号), 破坏与现有 Redis 数据的线格式兼容。
 *
 * 客户端形态差异 (hGetAll 的 `Map<String,String?>`、scan 游标为 String、option 的强类型封装等) 收敛在此处一处,
 * 调用点与门面接口保持不变。底层 re.this 内部连接池化, 单实例对并发命令 / pipeline 安全。
 */
internal class ReThisAronaRedis(
  private val client: ReThis,
) : AronaRedis {

  override suspend fun get(key: String): String? = client.get(key)

  override suspend fun set(key: String, value: String) {
    client.set(key, value)
  }

  override suspend fun setEx(key: String, value: String, ttlSeconds: Long) {
    client.set(key, value, SetExpire.Ex(ttlSeconds.seconds))
  }

  override suspend fun setNxEx(key: String, value: String, ttlSeconds: Long): Boolean =
    // 一次往返完成 SET NX EX: 成功占用 (NX 命中) 返回 "OK", key 已存在返回 null。
    client.set(key, value, UpsertMode.NX, SetExpire.Ex(ttlSeconds.seconds)) == "OK"

  override suspend fun del(vararg keys: String): Long = client.del(*keys)

  override suspend fun expire(key: String, ttlSeconds: Long): Boolean =
    client.expire(key, ttlSeconds.seconds)

  override suspend fun incr(key: String): Long = client.incr(key)

  override suspend fun hIncrBy(key: String, field: String, increment: Long): Long =
    client.hIncrBy(key, field, increment)

  override suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>): Long {
    require(fieldValues.isNotEmpty()) { "HSET 至少需要一对 field/value" }
    return client.hSet(key, *fieldValues.toFieldValues())
  }

  override suspend fun hGetAll(key: String): Map<String, String> {
    // re.this 的 hGetAll 返回 Map<String,String?>; 在此收敛成保序的 Map<String,String>。
    // HGETALL 的字段值在 Redis 永远是字符串、不会为 null; 一旦出现 null 即视为回复异常, 直接抛错暴露而非静默丢字段。
    // 用 LinkedHashMap 显式承载: 门面契约承诺保留服务端返回顺序 (keys.first() 等顺序敏感用法依赖此约定)。
    val source = client.hGetAll(key)
    val ordered = LinkedHashMap<String, String>(source.size)
    source.forEach { (field, value) ->
      ordered[field] = requireNotNull(value) { "redis HGETALL 字段 '$field' (key=$key) 返回 null" }
    }
    return ordered
  }

  override suspend fun hLen(key: String): Long = client.hLen(key)

  override suspend fun zAddGt(key: String, score: Double, member: String) {
    // ZMember 的构造顺序为 (member, score); comparison=GT 表示仅当新分数更大时才更新。返回值 (变更数) 忽略。
    client.zAdd(key, ZMember(member, score), comparison = UpdateStrategyOption.GT)
  }

  override suspend fun zRangeRev(key: String, start: Long, stop: Long, withScores: Boolean): List<String> =
    client.zRange(key, start.toString(), stop.toString(), rev = true, withScores = withScores)

  override suspend fun zScore(key: String, member: String): Double? = client.zScore(key, member)

  override suspend fun zRevRank(key: String, member: String): Long? = client.zRevRank(key, member)

  override suspend fun scan(cursor: String, match: String?, count: Long?): ScanPage {
    // re.this 正确编码 SCAN 选项: MATCH 缩小返回量、COUNT 提示批大小。游标输入为 Long、返回为 String。
    // match/count 为 null 时不下发对应选项。
    val options = buildList {
      match?.let { add(ScanOption.Match(it)) }
      count?.let { add(ScanOption.Count(it)) }
    }
    val page = client.scan(cursor.toLong(), *options.toTypedArray())
    return ScanPage(cursor = page.cursor, keys = page.keys)
  }

  override suspend fun pipeline(block: suspend RedisPipeline.() -> Unit) {
    // pipeline 块的 receiver 仍是 ReThis; 把门面写命令委托到该 receiver 上的原始层扩展函数。
    val results = client.pipeline {
      ReThisRedisPipeline(this).block()
    }
    // 单条命令的服务端错误被 re.this 作为 RType.Error 收进结果列表 (不抛出, 以免一条失败中断整批)。
    // "fire-and-forget" 仅指忽略成功返回值, 不等于忽略错误: 显式检查并抛出首个错误, 避免静默吞掉写失败 (如 WRONGTYPE)。
    results.filterIsInstance<RType.Error>().firstOrNull()?.let { throw it.exception }
  }

  /** pipeline 块内的写命令委托: 复用 pipeline receiver [ReThis] 上已导入的原始层扩展函数。 */
  private class ReThisRedisPipeline(
    private val delegate: ReThis,
  ) : RedisPipeline {
    override suspend fun incr(key: String) {
      delegate.incr(key)
    }

    override suspend fun hIncrBy(key: String, field: String, increment: Long) {
      delegate.hIncrBy(key, field, increment)
    }

    override suspend fun hSet(key: String, vararg fieldValues: Pair<String, String>) {
      require(fieldValues.isNotEmpty()) { "HSET 至少需要一对 field/value" }
      delegate.hSet(key, *fieldValues.toFieldValues())
    }
  }
}

/** 把门面的 (field, value) 对数组转换成 re.this 原始层 [FieldValue] 数组 (供 HSET vararg 展开)。 */
private fun Array<out Pair<String, String>>.toFieldValues(): Array<FieldValue> =
  Array(size) { i -> FieldValue(this[i].first, this[i].second) }
