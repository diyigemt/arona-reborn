package com.diyigemt.utils

import codes.laurence.warden.policy.bool.allOf
import org.junit.Test

class ABACTest {
  @Test
  fun testBase() {
    val policies = listOf(
      // Any User can read any Article
      allOf {
        resource("type") equalTo "Article"
        action("type") equalTo "READ"
      },
      // The author of an Article can read, modify and delete their Article
      allOf {
        resource("type") equalTo "Article"
        action("type") isIn listOf("MODIFY", "DELETE")
        subject("id") equalTo resource("authorID")
      },
      // A User must be an Author to be able to create an article.
      allOf {
        resource("type") equalTo "Article"
        action("type") equalTo "CREATE"
        subject("roles") contains "Author"
      }
    )
  }
}