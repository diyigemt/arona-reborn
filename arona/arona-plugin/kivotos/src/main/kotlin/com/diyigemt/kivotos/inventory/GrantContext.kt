package com.diyigemt.kivotos.inventory

import com.diyigemt.arona.utils.uuid

/**
 * 库存变更的来源描述与审计上下文.
 *
 * - [sourceType] 对接预留值: "system" / "command" / "event" / "admin"; 不用枚举以便活动临时注入新值
 * - [idempotencyKey] 机器人消息重投场景下的去重键, 非空时配合 Redis 占位
 * - [traceId] 关联事件、审计日志、外部系统的唯一追踪号, 默认自动生成
 */
data class GrantContext(
  val reason: String,
  val sourceType: String,
  val sourceId: String? = null,
  val idempotencyKey: String? = null,
  val traceId: String = uuid("inv"),
)
