package com.diyigemt.arona.permission.abac

import com.diyigemt.arona.database.permission.Policy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 自研 evaluator 的黄金用例 schema. 资源目录位于 src/test/resources/abac/golden.
 *
 * Phase 4 后 warden 基线不再存在, 所以 outcome 只取 allow/deny; 历史 (Phase 0) 的 error case
 * (对应 warden BadExpressionException 路径) 自研全部会映射为 deny, 故 GoldenOutcome 保留 error
 * 以兼容存量 JSON, 但运行时会当 deny 对齐.
 */
@Serializable
data class GoldenFile(
  val schemaVersion: Int,
  val cases: List<GoldenCase>,
)

@Serializable
data class GoldenCase(
  val id: String,
  val description: String,
  val policies: List<Policy>,
  val request: GoldenRequest,
  val expected: GoldenExpected,
)

@Serializable
data class GoldenRequest(
  val subject: Map<String, JsonElement> = emptyMap(),
  val action: Map<String, JsonElement> = emptyMap(),
  val resource: Map<String, JsonElement> = emptyMap(),
  val environment: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class GoldenExpected(
  val outcome: GoldenOutcome,
  val errorType: String? = null,
)

@Suppress("EnumEntryName")
@Serializable
enum class GoldenOutcome {
  allow,
  deny,
  error,
}
