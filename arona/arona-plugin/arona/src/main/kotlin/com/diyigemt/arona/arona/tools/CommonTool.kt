package com.diyigemt.arona.arona.tools

import com.diyigemt.arona.arona.database.DatabaseProvider.dbQuery
import com.diyigemt.arona.arona.database.name.TeacherName

fun queryTeacherNameFromDB(id: String): String {
  return dbQuery {
    TeacherName.findById(id)
  }?.name ?: "老师"
}

fun randomInt(bound: Int): Int = (System.currentTimeMillis() % bound).toInt()

fun randomBoolean(): Boolean = System.currentTimeMillis().toString().let {
  it.substring(it.length - 1).toInt() % 2 == 0
}
