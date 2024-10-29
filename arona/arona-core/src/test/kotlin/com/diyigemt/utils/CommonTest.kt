package com.diyigemt.utils

import com.diyigemt.arona.communication.message.TencentMessageIntentsBuilder
import com.diyigemt.arona.utils.*
import com.github.ajalt.clikt.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
  fun testIntentBuilder() {
    println(TencentMessageIntentsBuilder().buildPublicBot().build())
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
  fun testArgParse() {
    val tmp = "/测试 123  333 545 "
      .split(" ")
      .filter { it.isNotEmpty() }
      .toMutableList()
    println(tmp)
  }

  class B : CoroutineScope {
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
    suspend fun foo() {
      println("bar")
    }
  }

  class A {
    fun run(b: B) {
      b.launch {
        delay(1000)
        b.foo()
      }
    }
  }
  @Test
  fun testRunBlocking() {
    suspend fun bar() {
      val a = A()
      val b = B()
      a.run(b)
    }
    runBlocking {
      bar()
      println(1)
    }
  }
}
