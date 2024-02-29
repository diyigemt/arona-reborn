package com.diyigemt.utils

import com.diyigemt.arona.kts.host.evalFile
import com.diyigemt.arona.utils.commandLineLogger
import java.io.File
import kotlin.test.Test

class KtsTest {

  @Test
  fun testLoad() {
    evalFile(File("script/hello.main.kts"), mapOf("logger" to commandLineLogger))
  }
}
