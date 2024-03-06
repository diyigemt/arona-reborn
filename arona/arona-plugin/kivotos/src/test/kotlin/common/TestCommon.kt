package common

import org.junit.jupiter.api.Test

abstract class T {
  init {
    println("T init.")
  }
}

class F : T() {
  init {
    println("F init.")
  }
}

class TestCommon {
  @Test
  fun testInit() {
    val f = F()
    println(1)
  }
}
