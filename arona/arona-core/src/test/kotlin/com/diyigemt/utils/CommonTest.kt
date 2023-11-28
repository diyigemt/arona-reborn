package com.diyigemt.utils

import com.diyigemt.arona.communication.message.TencentRichMessage
import com.diyigemt.arona.utils.*
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

}
