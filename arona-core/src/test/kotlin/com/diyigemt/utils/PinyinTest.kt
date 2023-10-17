package com.diyigemt.utils

import com.diyigemt.arona.utils.PinyinUtil.stepString
import com.diyigemt.arona.utils.now
import me.towdium.pinin.PinIn
import me.towdium.pinin.searchers.Searcher
import me.towdium.pinin.searchers.TreeSearcher
import me.towdium.pinin.utils.PinyinFormat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.test.Test

class PinyinTest {
  @Test
  fun testSplitChar() {
    val p = PinIn()
    val target = "革命之伊万库帕拉国服"
    p.config().format(PinyinFormat.RAW).commit()
    val ch = target.toCharArray().joinToString(",") { p.format(p.getChar(it).pinyins()[0]) }
    println(ch)
    println(p.getChar('革').pinyins().joinToString(",") { p.format(it) })
    println(p.format(p.getChar('革').pinyins()[0]))
  }
  private fun string2Pinyin(s: String): List<String> {
    val p = PinIn()
    p.config().format(PinyinFormat.RAW).commit()
    return s.toCharArray().map { p.getChar(it).pinyins().map { pi -> p.format(pi) }.distinct() }.reduce { acc, strings ->
      acc * strings
    }
  }

  operator fun List<String>.times(other: List<String>): List<String> {
    return this.map {
      other.map { o -> "$it$o" }
    }.flatten()
  }
  @Test
  fun testDict() {
    val p = PinIn()
    p.config().format(PinyinFormat.RAW).commit()
    val treeSearcher = TreeSearcher<Int>(Searcher.Logic.CONTAIN, p)
    val ins = File("dict.txt")
    var index = 2
    ins.inputStream().use {
      InputStreamReader(it, StandardCharsets.UTF_8).use { isr ->
        BufferedReader(isr).use { br ->
          var line = br.readLine()
          while (line != null) {
            treeSearcher.put(line, index++)
            line = br.readLine()
          }
        }
      }
    }
    val start = now().toEpochMilliseconds()
    val t = "国际服千里眼"
    val tmp = stepString(t)
      .asSequence()
      .map { string2Pinyin(it)
        .map { pinyin -> treeSearcher.search(pinyin) }
      }
      .flatten()
      .flatten()
    val frequency = tmp
      .fold(mutableMapOf<Int, Int>()) { acc, i ->
      acc[i] = acc[i]?.inc() ?: 1
      acc
    }
    val r = tmp.sortedByDescending { frequency[it] }.distinct().toList()
    val end = now().toEpochMilliseconds()
    println(r)
    println("spend: ${end - start}ms")
  }
}
