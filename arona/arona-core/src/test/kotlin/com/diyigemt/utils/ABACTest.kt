package com.diyigemt.utils

import codes.laurence.warden.AccessRequest
import codes.laurence.warden.atts.HasAtts
import codes.laurence.warden.enforce.EnforcementPointDefault
import codes.laurence.warden.enforce.NotAuthorizedException
import codes.laurence.warden.policy.bool.allOf
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.ContactDocument.Companion.createContactDocument
import com.diyigemt.arona.database.permission.Policy.Companion.build
import kotlinx.coroutines.runBlocking
import org.junit.Test

data class User(
  val id: String,
  val type: String,
  val role: List<String> = listOf()
) : HasAtts()

data class Resource(
  val id: String,
) : HasAtts()

class ABACTest {
  @Test
  fun testBase() {
    runBlocking {
      val policies = listOf(
        allOf {
          resource("id") equalTo "com.diyigemt.arona:command.call_me"
          action("type") equalTo "effect"
          subject("type") equalTo "member"
          environment("time") greaterThan "15:19"
        },
        allOf {
          resource("id") equalTo "com.diyigemt.arona:command.call_me"
          action("type") equalTo "effect"
          subject("type") equalTo "admin"
        }
      )
      val p = listOf(
        allOf {
          allOf {
            resource("id") isChild "com.diyigemt.arona:*"
          }
          allOf {
            subject("type") equalTo "admin"
          }
        }
      )
      runCatching {
        EnforcementPointDefault(p).enforceAuthorization(
          AccessRequest(
            subject = User("", "member").atts(),
            action = mapOf("type" to "effect"),
            resource = Resource("com.diyigemt.arona:command.call_me").atts(),
            environment = mapOf("time" to "15:15")
          )
        )
      }.onSuccess {
        println("success")
      }
        .onFailure {
          if (it is NotAuthorizedException) {
            println(it.message)
          }
        }
    }
  }

  @Test
  fun testGeneratePolicy() {
    val rule1 = PolicyRule(
      PolicyRuleType.Resource,
      PolicyRuleOperator.IsCHILD,
      "id",
      "com.diyigemt.arona:*"
    )
    val rule2 = PolicyRule(
      PolicyRuleType.Subject,
      PolicyRuleOperator.Equal,
      "type",
      "member"
    )
    val rule3 = PolicyRule(
      PolicyRuleType.Environment,
      PolicyRuleOperator.GreaterThan,
      "time",
      "16:00"
    )
    val root = PolicyRoot(
      PolicyNodeGroupType.ALL,
      children = listOf(
        PolicyNode(
          PolicyNodeGroupType.ALL,
          listOf(rule1)
        ), PolicyNode(
          PolicyNodeGroupType.ALL,
          children = listOf(
            PolicyNode(
              PolicyNodeGroupType.ALL,
              listOf(rule2)
            ),
            PolicyNode(
              PolicyNodeGroupType.ALL,
              listOf(rule3)
            )
          )
        )
      )
    )
    val rule = Policy("1", "1", PolicyNodeEffect.ALLOW, listOf(root)).build()
    runBlocking {
      runCatching {
        EnforcementPointDefault(rule).enforceAuthorization(
          AccessRequest(
            subject = User("", "member").atts(),
            action = mapOf("type" to "effect"),
            resource = Resource("com.diyigemt.arona:command.call_me").atts(),
            environment = mapOf("time" to "16:15")
          )
        )
      }.onSuccess {
        println("success")
      }.onFailure {
        println("failed")
      }
    }
  }
  @Test
  fun testContactBaseAdminPolicy() {
    runBlocking {
      val contact = createContactDocument("123")
      val allow = contact.policies.filter { it.effect == PolicyNodeEffect.ALLOW }.map { it.build() }.flatten()
      val deny = contact.policies.filter { it.effect == PolicyNodeEffect.DENY }.map { it.build() }.flatten()
      runCatching {

        EnforcementPointDefault(allow, deny).enforceAuthorization(
          AccessRequest(
            subject = User("", "member", listOf(contact.roles[0].id)).atts(),
            action = mapOf("type" to "effect"),
            resource = Resource("com.diyigemt.arona:command.call_me").atts(),
            environment = mapOf("time" to "16:15")
          )
        )
      }.onSuccess {
        println("success")
      }.onFailure {
        println("failed")
      }
    }
  }
}
