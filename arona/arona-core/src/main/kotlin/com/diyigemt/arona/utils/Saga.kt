package com.diyigemt.arona.utils

/**
 * 跨库写操作的 best-effort 补偿模板.
 * 适用于"已经向 Mongo/Redis 写入但后续步骤可能失败"的场景, 弥补 4.x Mongo 没有跨集合事务的限制.
 *
 * - 调用方应当: 把"已经成功写入"的副作用列入 [rollback], 失败时尝试回滚.
 * - [rollback] 内的异常会被 [Throwable.addSuppressed] 附加到原异常上, 不会掩盖根因.
 * - 业务调用方仍应记录监控以发现"补偿也失败"的脏数据.
 */
internal suspend fun <T> runSagaOrRollback(
  rollback: suspend () -> Unit,
  action: suspend () -> T,
): T = try {
  action()
} catch (t: Throwable) {
  runCatching { rollback() }.onFailure { t.addSuppressed(it) }
  throw t
}
