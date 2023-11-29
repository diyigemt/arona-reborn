package com.diyigemt.arona.database.permission

import kotlinx.serialization.Serializable

enum class PolicyRuleType {
  ALLOW,
  DENY
}

@Serializable
internal data class PolicyRule(
  val id: String
)
@Serializable
internal data class Policy(
  val id: String,
  val creator: String,
  val name: String,
  val rules: List<PolicyRule>
)
@Serializable
internal data class ContactRole(
  val id: String,
  val name: String
)
@Serializable
internal data class ContactMember(
  val id: String,
  val name: String,
  val roles: List<String> // 指向ContactDocument.roles.id
)
@Serializable
internal data class ContactDocument(
  val id: String,
  val policies: List<Policy>,
  val roles: List<ContactRole>,
  val members: List<ContactMember>
)