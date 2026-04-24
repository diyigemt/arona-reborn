package com.diyigemt.arona.database.permission

import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_ADMIN_CONTACT_ROLE_ID
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.uuid
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

enum class PolicyNodeEffect {
  ALLOW,
  DENY
}

enum class PolicyNodeGroupType {
  ALL,
  ANY,
  NOT_ALL,
  NOT_ANY,
}

enum class PolicyRuleType {
  Resource,
  Action,
  Subject,
  Environment
}

enum class PolicyRuleOperator {
  Equal,
  LessThan,
  GreaterThan,
  LessThanEqual,
  GreaterThanEqual,
  Contains, // 将key对应值看作list
  ContainsAll, // 将key和value对应值看作list
  ContainsAny, // 将key和value对应值看作list
  IsIn, // 将value对应值看作list
  IsChild, // 检查形如 xxx.xxx.xx:* 的依赖关系, 语义见 arona-doc/docs/v2/abac/is-child-operator.md
}

@Serializable
data class PolicyRule(
  val type: PolicyRuleType,
  val operator: PolicyRuleOperator,
  val key: String,
  val value: String,
)

@Serializable
data class PolicyNode(
  val groupType: PolicyNodeGroupType,
  val rule: List<PolicyRule>? = null, // 当children == null是认为是叶子节点
  val children: List<PolicyNode>? = null,
)

typealias PolicyRoot = PolicyNode

@Serializable
data class Policy(
  @BsonId
  val id: String,
  val name: String,
  val effect: PolicyNodeEffect,
  val rules: List<PolicyRoot>,
) {
  companion object {
    internal const val PROTECTED_BUILD_IN_POLICY_ID = "policy.buildIn"
    internal val PROTECTED_POLICY_ID = listOf(
      PROTECTED_BUILD_IN_POLICY_ID
    )

    /**
     * 内置 DENY 策略: 当资源命中 `buildIn.super:*` 而 subject.id 不在 superAdminUid 列表时拒绝.
     * 自动注入到 [com.diyigemt.arona.permission.Permission.Companion.testPermission] 的 deny 列表.
     */
    val BuildInDenyPolicySchema: Policy by lazy {
      Policy(
        "policy.buildIn",
        "内置策略",
        effect = PolicyNodeEffect.DENY,
        rules = listOf(
          PolicyRoot(
            groupType = PolicyNodeGroupType.ALL,
            rule = listOf(
              PolicyRule(
                type = PolicyRuleType.Resource,
                operator = PolicyRuleOperator.IsChild,
                key = "id",
                value = "buildIn.super:*"
              )
            ),
            children = listOf(
              PolicyNode(
                groupType = PolicyNodeGroupType.NOT_ALL,
                rule = listOf(
                  PolicyRule(
                    type = PolicyRuleType.Subject,
                    operator = PolicyRuleOperator.IsIn,
                    key = "id",
                    value = aronaConfig.superAdminUidAsString,
                  )
                )
              )
            )
          )
        )
      )
    }

    fun randomPolicyId() = uuid("policy")

    fun createBaseContactAdminPolicy(): Policy {
      return Policy(
        id = "policy.admin",
        name = "管理员权限",
        effect = PolicyNodeEffect.ALLOW,
        rules = listOf(
          PolicyRoot(
            groupType = PolicyNodeGroupType.ALL,
            rule = listOf(
              PolicyRule(
                type = PolicyRuleType.Subject,
                operator = PolicyRuleOperator.Contains,
                key = "roles",
                value = DEFAULT_ADMIN_CONTACT_ROLE_ID
              ),
              PolicyRule(
                type = PolicyRuleType.Resource,
                operator = PolicyRuleOperator.IsChild,
                key = "id",
                value = "*"
              )
            )
          )
        )
      )
    }

    fun createBaseMemberPolicy(): List<Policy> {
      return listOf(
        Policy(
          id = "policy.default.allow",
          name = "普通成员权限",
          effect = PolicyNodeEffect.ALLOW,
          rules = listOf(
            PolicyRoot(
              groupType = PolicyNodeGroupType.ALL,
              rule = listOf(
                PolicyRule(
                  type = PolicyRuleType.Subject,
                  operator = PolicyRuleOperator.Contains,
                  key = "roles",
                  value = DEFAULT_MEMBER_CONTACT_ROLE_ID
                ),
                PolicyRule(
                  type = PolicyRuleType.Resource,
                  operator = PolicyRuleOperator.IsChild,
                  key = "id",
                  value = "*"
                )
              )
            )
          )
        ),
        Policy(
          id = "policy.default.deny",
          name = "普通成员不允许执行管理员指令",
          effect = PolicyNodeEffect.DENY,
          rules = listOf(
            PolicyRoot(
              groupType = PolicyNodeGroupType.ALL,
              rule = listOf(
                PolicyRule(
                  type = PolicyRuleType.Resource,
                  operator = PolicyRuleOperator.IsChild,
                  key = "id",
                  value = "buildIn.owner:*"
                )
              ),
              children = listOf(
                PolicyNode(
                  groupType = PolicyNodeGroupType.NOT_ALL,
                  rule = listOf(
                    PolicyRule(
                      type = PolicyRuleType.Subject,
                      operator = PolicyRuleOperator.Contains,
                      key = "roles",
                      value = DEFAULT_ADMIN_CONTACT_ROLE_ID
                    )
                  )
                )
              )
            )
          )
        )
      )
    }
  }
}
