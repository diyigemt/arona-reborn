package com.diyigemt.utils

import codes.laurence.warden.AccessRequest
import codes.laurence.warden.atts.HasAtts
import codes.laurence.warden.enforce.EnforcementPointDefault
import codes.laurence.warden.enforce.NotAuthorizedException
import codes.laurence.warden.policy.bool.allOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

data class User(
  val id: String,
  val type: String
) : HasAtts()

data class Resource(
  val id: String
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
      runCatching {
        EnforcementPointDefault(policies).enforceAuthorization(
          AccessRequest(
            subject = User("", "member").atts(),
            action = mapOf("type" to "effect"),
            resource = Resource("com.diyigemt.arona:command.call_me").atts(),
            environment = mapOf("time" to "15:15")
          )
        )
      }.onFailure {
        if (it is NotAuthorizedException) {
          println(it.message)
        }
      }
    }
  }
}
