package com.diyigemt.arona.chatbot

import kotlin.math.abs

/**
 * 纯文本回复分段器 (算法参考 AstrBot 社区插件 outputpro 的 split 阶梯, 本文件为独立重写).
 * 成对括号 / 引号与颜文字内部不产生切点; 候选切点超过段数上限时按累计长度均分目标 + 标点优先级挑选.
 * 只有确实拆成多段时才剥离段尾标点; 未拆分的文本原样返回, 保证单段路径与关闭分段时行为一致.
 */
internal object Segmenter {
  private const val NO_SPLIT = Int.MAX_VALUE

  /** 零宽空格包裹的占位符: 不参与配对/切分判定, 还原时整体替换回原颜文字. */
  private const val PLACEHOLDER_PREFIX = "\u200BKAOMOJI_"
  private const val PLACEHOLDER_SUFFIX = "\u200B"

  private val pairMap = mapOf(
    '(' to ')', '（' to '）', '[' to ']', '【' to '】', '{' to '}', '<' to '>',
    '《' to '》', '〈' to '〉', '「' to '」', '『' to '』', '“' to '”', '‘' to '’',
  )
  private val symmetricQuotes = setOf('"', '\'', '`')

  /** 第一支保护带括号的颜文字, 第二支保护无括号的连续表情符号; 长度设上限避免异常长文本回溯. */
  private val kaomojiPattern = Regex(
    """(?:[（(【\[<][^()\[\]（）【】<>]{0,40}[^\p{L}\p{N}\s][^()\[\]（）【】<>]{0,40}[）)】\]>])|(?:[△▦・ワ^><≧♥～｀❤]{2,15})""",
  )

  private data class Token(val text: String, val isSplit: Boolean, val priority: Int = NO_SPLIT)
  private data class SplitPoint(val tokenIndex: Int, val cumulativeLength: Int, val priority: Int)

  /**
   * 最多返回 [maxCount] 段. 没有可用切点或后处理后只剩一段时返回原文 (含首尾空白与标点);
   * 非空白输入至少返回一段, 空白输入返回空列表.
   */
  fun split(text: String, maxCount: Int): List<String> {
    if (text.isBlank()) return emptyList()
    val tokens = tokenize(text)
    val selected = selectSplitPoints(tokens, maxCount)
    if (selected.isEmpty()) return listOf(text)

    val raw = buildList {
      val current = StringBuilder()
      tokens.forEachIndexed { index, token ->
        current.append(token.text)
        if (index in selected) {
          add(current.toString())
          current.clear()
        }
      }
      if (current.isNotEmpty()) add(current.toString())
    }
    val processed = raw.map(::postProcess).filter { it.isNotEmpty() }
    return if (processed.size <= 1) listOf(text) else processed
  }

  /** 中日韩字符按完整打字时长, 其它按一半; 非空文本钳制到 [minMillis, maxMillis], 空文本为 0. 配置错值就地纠偏, 不抛异常. */
  fun delayMillis(text: String, perCharMillis: Long, minMillis: Long, maxMillis: Long): Long {
    if (text.isEmpty()) return 0
    val per = perCharMillis.coerceAtLeast(0)
    val max = maxMillis.coerceAtLeast(0)
    val min = minMillis.coerceIn(0, max)
    var total = 0L
    for (ch in text) {
      val charDelay = if (ch.isCjk()) per else per / 2
      // 先比较后累加 (饱和加法), 极端配置下不溢出.
      if (total >= max - charDelay) return max
      total += charDelay
    }
    return total.coerceAtLeast(min)
  }

  private fun tokenize(source: String): List<Token> {
    val mapping = linkedMapOf<String, String>()
    var placeholderIndex = 0
    val text = kaomojiPattern.replace(source) { match ->
      "$PLACEHOLDER_PREFIX${placeholderIndex++}$PLACEHOLDER_SUFFIX".also { mapping[it] = match.value }
    }
    fun restore(s: String) = mapping.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }

    val stack = mutableListOf<Char>()
    val tokens = mutableListOf<Token>()
    val buffer = StringBuilder()
    var i = 0
    while (i < text.length) {
      val ch = text[i]

      if (ch in symmetricQuotes) {
        // 英文缩写 / 所有格里的单引号不配对, 否则一个 don't 会屏蔽后续所有切点. 仅限 ASCII 字母数字之间
        // (don't / it's), 中文旁的直引号仍按配对引号处理.
        val apostropheInWord = ch == '\'' &&
          text.getOrNull(i - 1)?.isAsciiWord() == true && text.getOrNull(i + 1)?.isAsciiWord() == true
        if (!apostropheInWord) {
          if (stack.lastOrNull() == ch) stack.removeAt(stack.lastIndex) else stack.add(ch)
        }
        buffer.append(ch); i++; continue
      }

      val isOpener = ch in pairMap
      if (stack.isNotEmpty()) {
        when {
          ch == pairMap[stack.last()] -> stack.removeAt(stack.lastIndex)
          isOpener -> stack.add(ch)
        }
        buffer.append(ch); i++; continue
      }
      if (isOpener) {
        stack.add(ch)
        buffer.append(ch); i++; continue
      }

      if (splitPriority(ch) != NO_SPLIT) {
        // 连续切分符归入同一 token, 优先级取最强的那个.
        var priority = NO_SPLIT
        do {
          priority = minOf(priority, splitPriority(text[i]))
          buffer.append(text[i]); i++
        } while (i < text.length && splitPriority(text[i]) != NO_SPLIT)
        tokens.add(Token(restore(buffer.toString()), isSplit = true, priority))
        buffer.clear()
        continue
      }

      buffer.append(ch); i++
    }
    if (buffer.isNotEmpty()) tokens.add(Token(restore(buffer.toString()), isSplit = false))
    return tokens
  }

  /** 与 outputpro 一致: 候选不超上限时全用; 否则按累计长度均分目标选点, 优先级先于离目标的距离. */
  private fun selectSplitPoints(tokens: List<Token>, maxCount: Int): Set<Int> {
    val candidates = tokens.indices.filter { tokens[it].isSplit && tokens[it].text.isNotBlank() }
    if (candidates.isEmpty()) return emptySet()
    if (candidates.size <= maxCount - 1) return candidates.toSet()

    val totalLength = tokens.sumOf { it.text.length }
    val targets = (1 until maxCount).map { totalLength.toDouble() * it / maxCount }
    val points = buildList {
      var cumulative = 0
      tokens.forEachIndexed { index, token ->
        cumulative += token.text.length
        if (token.isSplit) add(SplitPoint(index, cumulative, token.priority))
      }
    }

    val selected = mutableSetOf<Int>()
    val window = mutableListOf<SplitPoint>()
    var cursor = 0
    for (target in targets) {
      while (cursor < points.size && points[cursor].cumulativeLength < target) window.add(points[cursor++])
      if (cursor < points.size) window.add(points[cursor++])
      if (window.isEmpty()) break
      val best = window.minWith(compareBy({ it.priority }, { abs(it.cumulativeLength - target) }, { it.tokenIndex }))
      selected.add(best.tokenIndex)
      window.removeAll { it.tokenIndex <= best.tokenIndex }
    }
    return selected
  }

  /** 剥离段尾的句号/逗号/分号 (真人聊天不打句号), 保留 ？！…～ 的语气. */
  private fun postProcess(text: String) = text.trim().trimEnd { it.isWhitespace() || it == '。' || it == '，' || it == '；' }

  private fun splitPriority(ch: Char) = when (ch) {
    '\n' -> 0
    '。', '？', '！', '…' -> 1
    '～', '；' -> 2
    else -> NO_SPLIT
  }

  private fun Char.isAsciiWord() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

  private fun Char.isCjk() =
    code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF ||
      code in 0x3040..0x30FF || code in 0x31F0..0x31FF ||
      code in 0x1100..0x11FF || code in 0x3130..0x318F || code in 0xAC00..0xD7AF
}
