@file:CompilerOptions("-jvm-target=17")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

package com.diyigemt.arona.test
import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import kotlinx.coroutines.runBlocking

val s = sender as UserCommandSender
val map = mutableListOf(
  mutableListOf(
    "k","k","k","k","k","k","k"
  ),
  mutableListOf(
    "k","k","k","k","e","f","b"
  ),
  mutableListOf(
    "k","p","f","f","k","k","k"
  ),
  mutableListOf(
    "k","k","k","k","k","k","k"
  ),
  mutableListOf(
    "k","k","k","k","k","k","k"
  ),
)

fun generateMap(m: List<List<String>>): TencentCustomKeyboard {
  return runBlocking {
    tencentCustomKeyboard(s.bot.unionOpenidOrId) {
      m.forEachIndexed { idx, r ->
        row {
          r.forEachIndexed { j, c ->
            button("$idx-$j") {
              render {
                label = when (c) {
                  "k" -> "▫\uFE0F"
                  "p" -> "❤\uFE0F"
                  "f" -> "⬜"
                  "e" -> "\uD83D\uDD3A"
                  "b" -> "\uD83D\uDD3B"
                  else -> "▫\uFE0F"
                }
              }
              action {
                data = "$idx-$j"
                type = TencentKeyboardButtonActionType.CALLBACK
              }
            }
          }
        }
      }
    }
  }
}
val md = tencentCustomMarkdown {
  h1("测试")
}
runBlocking {
  val kb = generateMap(map)
  MessageChainBuilder().append(md).append(kb).build().also {
    s.sendMessage(it)
  }
  val resp1 = s.nextButtonInteraction(5000L)
  resp1.accept()
  val click = resp1.buttonData.split("-").let {
    it[0].toInt() to it[1].toInt()
  }
  if (click.first != 2 || click.second !in listOf(2, 3)) {
    s.sendMessage("不能移动到${click.first}, ${click.second}")
  } else {
    map[2][1] = "f"
    map[click.first][click.second] = "p"
    MessageChainBuilder().append(md).append(generateMap(map)).build().also {
      s.sendMessage(it)
    }
  }
}
