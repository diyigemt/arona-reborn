package com.diyigemt.utils

import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.currentTime
import kotlin.reflect.full.declaredMembers
import kotlin.test.Test

class CommonTest {
  @Test
  fun testDatetime() {
    println(currentDate())
    println(currentTime())
    println(currentDateTime())
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

  interface A {
    fun save() {
      println(this::class.declaredMembers.map { it.name })
    }
  }

  object B : A {
    var name: String = "a"
  }

  @Test
  fun testInterfaceClass() {
    B.save()
  }
}
