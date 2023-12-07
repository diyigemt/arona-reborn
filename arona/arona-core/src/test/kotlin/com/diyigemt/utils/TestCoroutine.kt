package com.diyigemt.utils

import kotlinx.coroutines.*
import kotlin.test.Test

class TestCoroutine {
  @Test
  fun testCoroutineName() {
    runBlocking {
      CoroutineScope(CoroutineName("test")).launch {
        println(
          currentCoroutineContext()[CoroutineName]?.name
        )
        CoroutineScope(currentCoroutineContext() + CoroutineName("test2")).launch {
          println(
            currentCoroutineContext()[CoroutineName]?.name
          )
        }.join()
      }.join()
    }
  }

}
