package com.diyigemt.arona.database.permission

import codes.laurence.warden.policy.bool.AllOf
import codes.laurence.warden.policy.bool.AnyOf
import codes.laurence.warden.policy.bool.allOf
import codes.laurence.warden.policy.bool.anyOf
import codes.laurence.warden.policy.collections.CollectionBasedPolicy
import com.diyigemt.arona.database.permission.PolicyNode.Companion.build
import com.diyigemt.arona.database.permission.PolicyRule.Companion.build
import kotlinx.serialization.Serializable
import codes.laurence.warden.policy.Policy as P

enum class PolicyNodeEffect {
  ALLOW,
  DENY
}

enum class PolicyNodeGroupType {
  ALL,
  ANY
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
  IsCHILD, // 检查形如  xxx.xxx.xx:*的依赖关系
}

@Serializable
internal data class PolicyRule(
  val type: PolicyRuleType,
  val operator: PolicyRuleOperator,
  val key: String,
  val value: String,
) {
  companion object {
    fun PolicyRule.build(parent: CollectionBasedPolicy) {
      with(parent) {
        val left = when (type) {
          PolicyRuleType.Resource -> resource(key)
          PolicyRuleType.Action -> action(key)
          PolicyRuleType.Subject -> subject(key)
          PolicyRuleType.Environment -> environment(key)
        }
        when (operator) {
          PolicyRuleOperator.Equal -> left equalTo value
          PolicyRuleOperator.LessThan -> left lessThan value
          PolicyRuleOperator.GreaterThan -> left greaterThan value
          PolicyRuleOperator.LessThanEqual -> left lessThanEqual value
          PolicyRuleOperator.GreaterThanEqual -> left greaterThanEqual value
          PolicyRuleOperator.Contains -> left contains value
          PolicyRuleOperator.ContainsAll -> left containsAll value.split(",")
          PolicyRuleOperator.ContainsAny -> left containsAny value.split(",")
          PolicyRuleOperator.IsIn -> left isIn value.split(",")
          PolicyRuleOperator.IsCHILD -> left isChild value
        }
      }
    }
  }
}

@Serializable
internal data class PolicyNode(
  val groupType: PolicyNodeGroupType,
  val rule: List<PolicyRule>? = null, // 当children == null是认为是叶子节点
  val children: List<PolicyNode>? = null,
) {
  companion object {

    /**
     * allow to deny
     */
    internal fun PolicyNode.build(): P {
      val father = if (groupType == PolicyNodeGroupType.ALL) AllOf(mutableListOf()) else AnyOf(mutableListOf())
      rule?.forEach { it.build(father) }
      children?.forEach { father.policies.add(it.build()) }
      return father
    }

  }
}
internal typealias PolicyRoot = PolicyNode

@Serializable
internal data class Policy(
  val id: String,
  val name: String,
  val effect: PolicyNodeEffect,
  val rules: List<PolicyRoot>,
) {
  companion object {

    internal fun Policy.build(): List<P> {
      val base = rules.map {
        it.build()
      }
      return base
    }

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
                key = "role",
                value = "role.admin"
              ),
              PolicyRule(
                type = PolicyRuleType.Resource,
                operator = PolicyRuleOperator.IsCHILD,
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
          id = "role.default.allow",
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
                  value = "role.default"
                ),
                PolicyRule(
                  type = PolicyRuleType.Resource,
                  operator = PolicyRuleOperator.IsCHILD,
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
                  type = PolicyRuleType.Subject,
                  operator = PolicyRuleOperator.Contains,
                  key = "roles",
                  value = "role.default"
                ),
                PolicyRule(
                  type = PolicyRuleType.Resource,
                  operator = PolicyRuleOperator.IsCHILD,
                  key = "id",
                  value = "buildIn.owner.*"
                )
              )
            )
          )
        )
      )
    }
  }
}
