@file:CompilerOptions("-jvm-target=17")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

import com.diyigemt.arona.command.nextButtonInteraction
import com.diyigemt.arona.communication.command.UserCommandSender
import com.diyigemt.arona.communication.message.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

val s = sender as UserCommandSender

class GameMap(val w: Int, val h: Int) {
  val map = (0 until h).map {
    (0 until w).map {
      "k"
    }.toMutableList()
  }.toMutableList()
  var x: Int = 0
  var y: Int = 0
  val vw: Int = 8
  val vh: Int = 5
  var end = false
  fun set(x: Int, y: Int, t: String) {
    if (x < 0 || y < 0) return
    map[y][x] = t
  }

  fun render(): List<List<String>> {
    val dx = x - vw / 2
    val dy = y - vh / 2
    return (dy until dy + vh).map { y ->
      (dx until dx + vw).map { x ->
        if (x < 0 || y < 0) {
          "k"
        } else {
          map[y][x]
        }
      }
    }.map { r ->
      r.map { c ->
        when (c) {
          "k" -> "▫\uFE0F"
          "p" -> "❤\uFE0F"
          "f" -> "⬜"
          "e" -> "\uD83D\uDD3A"
          "b" -> "\uD83D\uDD3B"
          else -> "▫\uFE0F"
        }
      }
    }
  }

  fun move(x: Int, y: Int): Boolean {
    val tx = this.x - vw / 2 + x
    val ty = this.y - vh / 2 + y
    if (tx < 0 || ty < 0) {
      return false
    }
    return if (map[ty][tx] == "k") {
      false
    } else {
      end = map[ty][tx] == "e"
      map[this.y][this.x] = "f"
      map[ty][tx] = "p"
      this.x = tx
      this.y = ty
      true
    }
  }
}

val gm = GameMap(15, 10)
val m = """
      k,k,k,k,k,k,k,k,k,k,k,k,k,k,k
      k,k,k,k,k,k,k,k,k,k,k,k,k,k,k
      k,k,k,k,k,k,k,k,k,k,k,k,k,k,k
      k,k,k,e,k,k,e,k,k,k,k,k,k,k,k
      k,k,k,f,f,f,f,k,k,k,k,k,k,k,k
      k,p,f,f,k,k,k,f,k,k,k,k,k,k,k
      k,k,k,k,f,k,k,k,b,k,k,k,k,k,k
      k,k,k,k,k,f,k,k,k,k,k,k,k,k,k
      k,k,k,k,k,e,f,f,e,k,k,k,k,k,k
      k,k,k,k,k,k,k,k,k,k,k,k,k,k,k
    """.trimIndent()
m.split("\n").forEachIndexed { y, r ->
  r.split(",").forEachIndexed { x, c ->
    gm.set(x, y, c)
  }
}
gm.move(5, 7)

val md = tencentCustomMarkdown {
  h1("测试")
  + "❤\uFE0F是玩家"
  + "⬜是能踩的地板"
  + "\uD83D\uDD3A是目标地点"
}

runBlocking {
  fun gBtn(gm: GameMap): TencentCustomKeyboard {
    return tencentCustomKeyboard(s.bot.unionOpenidOrId) {
      gm.render().forEachIndexed { idx, r ->
        row {
          r.forEachIndexed { j, c ->
            button("$j-$idx") {
              render {
                label = c
              }
              action {
                data = "$j-$idx"
                type = TencentKeyboardButtonActionType.CALLBACK
              }
            }
          }
        }
      }
    }
  }
  val message = MessageChainBuilder().append(md).append(gBtn(gm)).build()
  s.sendMessage(message)
  do {
    val resp = withTimeoutOrNull(5000L) {
      s.nextButtonInteraction()
    }
    if (resp == null) {
      s.sendMessage("超时")
      break
    }
    resp.accept()
    val xy = resp.buttonData.split("-").let {
      it[0].toInt() to it[1].toInt()
    }
    val tip =
      if (gm.move(xy.first, xy.second)) {
        "移动成功"
      } else {
        "不能移动到那"
      }
    MessageChainBuilder().append(
      tencentCustomMarkdown {
        h1(tip)
      }
    ).append(gBtn(gm)).build().also {
      s.sendMessage(it)
    }
  } while (!gm.end)
  s.sendMessage("结束")
}
