package com.diyigemt.utils

import com.diyigemt.arona.communication.message.TencentMessageIntentsBuilder
import com.diyigemt.arona.communication.message.TencentRichMessage
import com.diyigemt.arona.utils.*
import com.github.ajalt.clikt.core.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test

class CommonTest {
  @Test
  fun testDatetime() {
    println(currentDate())
    println(currentTime())
    println(currentDateTime())
    println(now().plus(60, DateTimeUnit.SECOND).toDateTime())
  }

  @Test
  fun testApply() {
    fun a(block: () -> Unit) {
      block.apply {
        println("b")
        this()
      }
    }
  }

  @Test
  fun testBitOp() {
    val a = 1 shl 0 or 1 shl 9 or 1 shl 1
    println(1 shl 0 or 1 shl 9 or 1 shl 1)
  }

  @Test
  fun testIntentBuilder() {
    println(TencentMessageIntentsBuilder().buildPublicBot().build())
  }

  @Test
  fun testJson() {
    println(Json.encodeToString(TencentRichMessage("123")))
  }

  @Test
  fun testException() {
    runCatching {
      throw Exception("")
    }.onFailure { println(1) }
      .getOrElse { println(2) }
  }

  @Test
  fun testContext() {
    val a = object : CliktCommand(invokeWithoutSubcommand = true) {
      override fun run() {
        currentContext.findOrSetObject("i") {
          1
        }
        currentContext.findOrSetObject("j") {
          "1"
        }
        println("father")
      }
    }
    val b = object : CliktCommand(name = "c") {
      private val i by requireObject<String>("j")
      private val j by requireObject<Int>("i")

      override fun run() {
        println("child: $i $j")
      }
    }
    a.subcommands(b)
    a.parse(listOf("c"))
  }
  @Test
  fun testDuration() {
    val a = "2024-03-18 16:14:00".toInstant()
    val b = "2024-03-18 19:13:00".toInstant()
    println((b - a).inWholeHours)
  }
}
