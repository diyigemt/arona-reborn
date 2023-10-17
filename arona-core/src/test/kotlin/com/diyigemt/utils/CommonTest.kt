package com.diyigemt.utils

import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.currentTime
import kotlin.test.Test

class CommonTest {
  @Test
  fun testDatetime() {
    println(currentDate())
    println(currentTime())
    println(currentDateTime())
  }
}
