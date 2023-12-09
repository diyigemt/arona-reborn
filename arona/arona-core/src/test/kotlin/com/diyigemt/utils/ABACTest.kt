package com.diyigemt.utils

import codes.laurence.warden.AccessRequest
import codes.laurence.warden.atts.HasAtts
import codes.laurence.warden.enforce.EnforcementPointDefault
import codes.laurence.warden.enforce.NotAuthorizedException
import codes.laurence.warden.policy.bool.allOf
import com.diyigemt.arona.database.permission.*
import com.diyigemt.arona.database.permission.ContactDocument.Companion.createContactDocument
import com.diyigemt.arona.database.permission.ContactDocument.Companion.findContactDocumentByIdOrNull
import com.diyigemt.arona.database.permission.Policy.Companion.build
import com.diyigemt.arona.permission.Permission.Companion.RootPermission
import com.diyigemt.arona.permission.Permission.Companion.fullPermissionId
import com.diyigemt.arona.permission.Permission.Companion.testPermission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionImpl
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
      val contact = findContactDocumentByIdOrNull("") ?: return@runBlocking
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
  @Test
  fun testPermissionFather() {
    val root = PermissionImpl(PermissionId("buildIn", "*"), "root permission", RootPermission)
    val firstChild = PermissionImpl(PermissionId("command.bind", "*"), "第一个子代", root)
    val secondChild = PermissionImpl(PermissionId("bind_a", "*"), "第二个子代", firstChild)
    println(root.fullPermissionId())
    println(firstChild.fullPermissionId())
    println(secondChild.fullPermissionId())
  }
  @Test
  fun testCheckPermission() {
    runBlocking {
      val contact = findContactDocumentByIdOrNull("") ?: return@runBlocking
      val member = ContactMember("id", "成员", listOf("role.default"))
      val permission = PermissionImpl(PermissionId("buildIn.owner", "admin"), "", RootPermission)
      val permission2 = PermissionImpl(PermissionId("com.diyigemt.arona", "*"), "", RootPermission)
      println(permission.testPermission(member, contact.policies))
      println(permission2.testPermission(member, contact.policies))
    }

  }
  @Test
  fun testPermissionIdMatch() {
    val r = "buildIn.*"
    val l = "buildIn.owner:min.action:test.m"
    if (r == "*") {
      println("t")
      return
    }
    val rL = r.split(":")
    val lL = l.split(":")
    fun test(right: String, left: String): Boolean {
      return if (right.endsWith("*")) {
        if (right == "*") true
        else {
          val leftList = left.split(".")
          val rightList = right.split(".")
          if (leftList.size < rightList.size) false
          else if (leftList.size == rightList.size && rightList.last() != "*") left == right
          else {
            rightList.mapIndexed { i, v ->
              leftList[i] == v
            }.toMutableList().also {
              it.removeLast()
            }.reduce { acc, b -> acc && b } && rightList.last() == "*"
          }
        }
      } else {
        right == left
      }
    }
    val b = if (rL.size > lL.size) false else {
      rL.mapIndexed { i, v ->
        test(v, lL[i])
      }.reduceOrNull { acc, b -> acc && b } ?: false
    }
    println(b)
  }
}
