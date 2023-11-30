package com.diyigemt.arona.database.permission

import codes.laurence.warden.policy.bool.allOf
import codes.laurence.warden.policy.bool.anyOf
import codes.laurence.warden.policy.bool.not
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
  val effect: PolicyNodeEffect,
  val groupType: PolicyNodeGroupType,
  val rule: PolicyRule? = null, // 当children == null是认为是叶子节点
  val children: List<PolicyNode>? = null,
) {
  companion object {
    internal val PolicyNode.isLeaf get() = children == null && rule != null
    internal val PolicyNode.isNode get() = children != null
    internal fun PolicyNode.build(parent: CollectionBasedPolicy? = null): List<P> {
      return if (parent == null) {
        // root
        if (isLeaf) {
          CollectionBasedPolicy(mutableListOf()).also {
            rule!!.build(it)
          }.policies
        } else {
          val base = when (groupType) {
            PolicyNodeGroupType.ALL -> {
              allOf {
                children!!.forEach {
                  it.build(this@allOf)
                }
              }
            }

            PolicyNodeGroupType.ANY -> {
              anyOf {
                children!!.forEach {
                  it.build(this@anyOf)
                }
              }
            }
          } as P
          when (effect) {
            PolicyNodeEffect.ALLOW -> listOf(base)
            PolicyNodeEffect.DENY -> listOf(not(base))
          }
        }
      } else {
        // node
        if (isLeaf) {
          rule!!.build(parent)
          when (effect) {
            PolicyNodeEffect.ALLOW -> {}
            PolicyNodeEffect.DENY -> parent.policies.removeLast().also {
              parent.add(not(it))
            }
          }
        } else {
          children!!.forEach {
            it.build(parent)
          }
        }
        parent.policies
      }
    }

  }
}
internal typealias PolicyRoot = PolicyNode

@Serializable
internal data class Policy(
  val id: String,
  val name: String,
  val rules: List<PolicyRoot>,
) {
  companion object {
    internal fun Policy.build(): List<codes.laurence.warden.policy.Policy> {
      return rules.map {
        it.build()
      }.flatten()
    }
  }
}

@Serializable
internal data class ContactRole(
  val id: String,
  val name: String,
)

@Serializable
internal data class ContactMember(
  val id: String,
  val name: String,
  val roles: List<String>, // 指向ContactDocument.roles.id
)

@Serializable
internal data class ContactDocument(
  val id: String,
  val policies: List<Policy>,
  val roles: List<ContactRole>,
  val members: List<ContactMember>,
)
