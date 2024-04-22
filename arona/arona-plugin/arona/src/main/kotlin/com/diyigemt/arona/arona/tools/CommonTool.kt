package com.diyigemt.arona.arona.tools

import com.diyigemt.arona.communication.command.UserCommandSender

suspend fun UserCommandSender.queryTeacherNameFromDB(): String {
  val tmp = userDocument().username
  return if (!tmp.endsWith("老师")) {
    "老师"
  } else {
    tmp
  }
}

fun randomInt(bound: Int): Int = (System.currentTimeMillis() % bound).toInt()

fun randomBoolean(): Boolean = System.currentTimeMillis().toString().let {
  it.substring(it.length - 1).toInt() % 2 == 0
}
