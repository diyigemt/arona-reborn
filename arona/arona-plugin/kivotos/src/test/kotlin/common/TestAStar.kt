package common

import org.junit.jupiter.api.Test
import java.util.*

class TestAStar {
  data class Node(val x: Int, val y: Int, var g: Int = 0, var h: Int = 0, var parent: Node? = null) {
    override fun equals(other: Any?): Boolean {
      return other is Node && x == other.x && y == other.y
    }
  }
  @Test
  fun main() {
    val map = arrayOf(
      intArrayOf(0, 0, 0, 0, 0),
      intArrayOf(1, 1, 0, 1, 0),
      intArrayOf(0, 0, 0, 0, 0),
      intArrayOf(0, 1, 1, 1, 1),
      intArrayOf(0, 0, 0, 0, 0)
    )

    val start = Node(0, 0)
    val end = Node(4, 4)

    val path = findPath(map, start, end)
    println("Path found: ${path.joinToString(", ") { "(${it.x}, ${it.y})" }}")
  }

  fun findPath(map: Array<IntArray>, start: Node, end: Node): List<Node> {
    val openList = PriorityQueue<Node>(compareBy { it.g + it.h })
    val closedList = mutableSetOf<Node>()

    openList.add(start)

    while (openList.isNotEmpty()) {
      val current = openList.poll()

      if (current == end) {
        val path = mutableListOf<Node>()
        var temp: Node? = current
        while (temp != null) {
          path.add(temp)
          temp = temp.parent
        }
        return path.reversed()
      }

      closedList.add(current)

      val neighbors = getNeighbors(current, map)
      for (neighbor in neighbors) {
        if (neighbor in closedList) {
          continue
        }

        val tentativeG = current.g + 1
        if (neighbor !in openList || tentativeG < neighbor.g) {
          neighbor.g = tentativeG
          neighbor.h = calculateHeuristic(neighbor, end)
          neighbor.parent = current
          if (neighbor !in openList) {
            openList.add(neighbor)
          }
        }
      }
    }

    return emptyList()
  }

  fun getNeighbors(node: Node, map: Array<IntArray>): List<Node> {
    val neighbors = mutableListOf<Node>()
    val dx = intArrayOf(-1, 0, 1, 0, -1, -1, 1, 1)
    val dy = intArrayOf(0, 1, 0, -1, -1, 1, -1, 1)

    for (i in 0 until 8) {
      val newX = node.x + dx[i]
      val newY = node.y + dy[i]
      if (isValid(newX, newY, map)) {
        neighbors.add(Node(newX, newY))
      }
    }

    return neighbors
  }

  fun isValid(x: Int, y: Int, map: Array<IntArray>): Boolean {
    return x in map.indices && y in map[0].indices && map[x][y] == 0
  }

  fun calculateHeuristic(current: Node, end: Node): Int {
    return Math.abs(current.x - end.x) + Math.abs(current.y - end.y)
  }

}
