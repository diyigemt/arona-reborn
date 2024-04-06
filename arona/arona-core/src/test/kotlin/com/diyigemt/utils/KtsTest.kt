package com.diyigemt.utils

import com.diyigemt.arona.communication.message.button
import com.diyigemt.arona.communication.message.render
import com.diyigemt.arona.communication.message.row
import com.diyigemt.arona.communication.message.tencentCustomKeyboard
import com.diyigemt.arona.kts.host.evalFile
import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.valueOrNull
import kotlin.test.Test

data class GameMap(
  val map: List<List<GameCell>>
) {
  fun render() = tencentCustomKeyboard("") {
    map.forEachIndexed { idx, r ->
      row {
        r.forEachIndexed { j, c ->
          button("$idx-$j") {
            render {
              label = c.type.text
            }
          }
        }
      }
     }
  }
}

data class GameCell(
  val type: GameCellType
)

enum class GameCellType(val text: String) {
  Empty("x"), Enemy("e"), Boss("b"), Floor(" "), Player("p");
  companion object {
    private val map = entries.associateBy { it.text }
    fun fromText(t: String) = map[t] ?: Empty
  }
}

class KtsTest {

  fun testLoad() {
    val result = evalFile(File("script/hello.main.kts"))
    when (val r = result.valueOrNull()?.returnValue) {
      is ResultValue.Value -> println(r.value)
      else -> {}
    }
  }

  @Test
  fun testIndent() {
    println("""
      function foo() {
        console.log("bar");
      }
    """.trimIndent())
  }
}
