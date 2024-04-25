import org.junit.jupiter.api.Test

class TestGame {
  @Test
  fun testGameMap() {
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
    gm.move(6, 7)
    gm.render().forEach { r ->
      r.forEach { c ->
        print(c)
      }
      println()
    }
  }
}

class GameMap(val w: Int, val h: Int) {
  val map = (0 until h).map {
    (0 until w).map {
      "k"
    }.toMutableList()
  }.toMutableList()
  var x: Int = 0
  var y: Int = 0
  val vw: Int = 10
  val vh: Int = 5
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
      map[this.y][this.x] = "f"
      this.x = tx
      this.y = ty
      true
    }
  }
}
