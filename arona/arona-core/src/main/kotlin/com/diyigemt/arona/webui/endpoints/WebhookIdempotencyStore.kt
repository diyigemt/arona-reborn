package com.diyigemt.arona.webui.endpoints

import com.diyigemt.arona.database.DatabaseProvider.redisDbQuery
import io.github.crackthecodeabhi.kreds.args.SetOption
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

internal const val WebhookIdempotencyKeyPrefix = "webhook:idempotency:"
internal const val WebhookIdempotencyTtlSeconds: UInt = 300u

/**
 * 原子 claim 抽象: 生产走 Redis SET NX EX, 测试可注入内存实现避免依赖真实 Redis.
 *
 * 返回 `true` 表示本次是**首次占用** key, 后续持有权归调用方; 返回 `false` 表示 key 已被占用 (重复).
 */
internal interface IdempotencyClaim {
  suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean
}

/**
 * Redis 实现: `SET key "1" EX ttl NX`——NX 条件不满足时 Redis 返回 nil, kreds 映射为 `null`,
 * 满足时返回 "OK". 任何 I/O 或协议异常由调用方 [WebhookIdempotencyStore] 捕获降级.
 */
internal object RedisIdempotencyClaim : IdempotencyClaim {
  override suspend fun tryClaim(key: String, ttlSeconds: UInt): Boolean =
    redisDbQuery {
      set(
        key,
        "1",
        SetOption.Builder()
          .exSeconds(ttlSeconds.toULong())
          .nx(true)
          .build(),
      ) == "OK"
    }
}

/**
 * Webhook 入站幂等存储. `shouldDispatch` 语义:
 *   - `true`  : 放行, 允许继续分派 (首次 / payloadId 为空 / Redis 异常降级);
 *   - `false` : 拦截, 已被占用 (命中幂等窗口), 调用方应直接响应成功不再分派.
 *
 * 失败降级为"放行": 腾讯 webhook 超过 5s 无响应会重试, 若因 Redis 异常吃掉事件, 用户体验损失大于重复分派.
 * payloadId 为空的事件 (例如无 id 的特殊事件) 同样放行——只是丢失本次幂等保护, 不阻塞主路径.
 * 签名哈希 fallback 暂不实现 (不同事件共用同一签名窗口会误合并).
 */
internal class WebhookIdempotencyStore(
  private val claim: IdempotencyClaim,
  private val logger: Logger = KtorSimpleLogger("com.diyigemt.arona.webui.endpoints.WebhookIdempotencyStore"),
  private val keyPrefix: String = WebhookIdempotencyKeyPrefix,
  private val ttlSeconds: UInt = WebhookIdempotencyTtlSeconds,
) {
  suspend fun shouldDispatch(payloadId: String?): Boolean {
    if (payloadId.isNullOrBlank()) return true
    return runCatching {
      claim.tryClaim(keyPrefix + payloadId, ttlSeconds)
    }.getOrElse {
      // CancellationException 是协作式取消信号, 必须透传给 ktor 请求 scope;
      // 吞掉会让被取消的请求误走 fail-open 分支继续 dispatch.
      if (it is CancellationException) throw it
      logger.warn("webhook idempotency unavailable, degrading to pass-through. payloadId=$payloadId", it)
      true
    }
  }
}
