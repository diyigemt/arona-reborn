package com.diyigemt


import com.diyigemt.host.evalFile
import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.valueOrNull

data class TestDataTransfer(
  val value: Int
)

fun main(vararg args: String) {
  val scriptFile = File("test.main.kts")
  println("Executing script $scriptFile")
  val first = kotlin.system.measureTimeMillis {
    evalFile(scriptFile).also {
      when (val u = it.valueOrNull()?.returnValue) {
        is ResultValue.Value -> {
          println((u.value as? TestDataTransfer)?.value)
        }
        else -> {}
      }
    }
  }
  val second = kotlin.system.measureTimeMillis {
    evalFile(scriptFile)
  }
  println("first: $first, second: $second")
}
