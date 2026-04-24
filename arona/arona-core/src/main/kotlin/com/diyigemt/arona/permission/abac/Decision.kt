package com.diyigemt.arona.permission.abac

/**
 * 自研 evaluator 的求值结果. 相较 warden 的 "抛异常/不抛异常" 二元, 这里用 sealed 表达四种结局:
 * Permit / Deny(NoAllowMatched) / Deny(DenyMatched) / Deny(EvalError).
 *
 * 上层 [com.diyigemt.arona.permission.Permission.Companion.testPermission] 只关心布尔结果, 但
 * `/policy/preview` endpoint 和日志需要进一步区分原因, 所以暴露结构化 Decision.
 */
internal sealed interface Decision {
  val reason: String

  data class Permit(
    val hitAllowPolicyId: String,
    override val reason: String,
  ) : Decision

  data class Deny(
    val kind: Kind,
    override val reason: String,
    val hitDenyPolicyId: String? = null,
  ) : Decision {
    enum class Kind {
      /** 无任何 allow policy 命中 (同时也未命中 deny). */
      NoAllowMatched,

      /** 至少一条 deny policy 命中. */
      DenyMatched,

      /** 求值时抛异常 (通常表示 policy 配置错误或 request 数据异常). */
      EvalError,
    }
  }
}
