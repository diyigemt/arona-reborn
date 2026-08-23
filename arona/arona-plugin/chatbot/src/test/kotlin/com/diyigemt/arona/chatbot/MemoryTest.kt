package com.diyigemt.arona.chatbot

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 记忆压缩的纯函数: 批次规划 (水位线边界精确性) 与摘要 prompt 装配.
class MemoryTest {
  private fun line(id: Int, ts: Long, fromBot: Boolean = false) =
    ChatLine("$id", "g", if (fromBot) BOT_SENDER_ID else "u$id", if (fromBot) null else "群友$id", "m$id", fromBot, Date(ts))

  @Test
  fun `保留最新 keepRecent 行, 单批不超过 batchLimit`() {
    val rows = (1..10).map { line(it, it * 1000L) }
    val plan = assertNotNull(planCompression(rows, keepRecent = 3, batchLimit = 5))
    assertEquals(listOf("1", "2", "3", "4", "5"), plan.lines.map { it.id })
    assertEquals(Date(5000L), plan.coveredUntil)

    val small = assertNotNull(planCompression(rows, keepRecent = 3, batchLimit = 100))
    assertEquals(7, small.lines.size)
    assertEquals(Date(7000L), small.coveredUntil)
  }

  @Test
  fun `行数不够保留窗口时不压缩`() {
    val rows = (1..5).map { line(it, it * 1000L) }
    assertNull(planCompression(rows, keepRecent = 5, batchLimit = 100))
    assertNull(planCompression(rows, keepRecent = 20, batchLimit = 100))
    assertNull(planCompression(emptyList(), keepRecent = 0, batchLimit = 100))
  }

  @Test
  fun `与批次末行同毫秒的行整体留到下一批, 水位线不切开同毫秒`() {
    // 第 4/5/6 行同毫秒, 批次上限 5 会切在第 5 行: 4/5 必须退出本批, 第 6 行下次才能被 ts > coveredUntil 选中.
    val rows = listOf(line(1, 1000), line(2, 2000), line(3, 3000), line(4, 4000), line(5, 4000), line(6, 4000), line(7, 5000))
    val plan = assertNotNull(planCompression(rows, keepRecent = 1, batchLimit = 5))
    assertEquals(listOf("1", "2", "3"), plan.lines.map { it.id })
    assertEquals(Date(3999), plan.coveredUntil)
    assertTrue(rows.filter { it.ts.after(plan.coveredUntil) }.map { it.id } == listOf("4", "5", "6", "7"))
  }

  @Test
  fun `整批同毫秒的退化情况整体压缩`() {
    val rows = (1..4).map { line(it, 1000) }
    val plan = assertNotNull(planCompression(rows, keepRecent = 1, batchLimit = 10))
    assertEquals(listOf("1", "2", "3"), plan.lines.map { it.id })
    assertEquals(Date(1000), plan.coveredUntil)
  }

  @Test
  fun `摘要 prompt 带旧摘要与记录, bot 行标为我`() {
    val rows = listOf(line(1, 1000), line(2, 2000, fromBot = true))
    val prompt = buildSummaryPrompt("小明爱吃鱼", rows, 600)
    assertTrue(prompt.startsWith("旧摘要:\n小明爱吃鱼"))
    assertTrue(prompt.contains("群友1: m1"))
    assertTrue(prompt.contains("我: m2"))
    assertTrue(prompt.endsWith("输出不超过 600 字的新摘要."))
    assertTrue(!buildSummaryPrompt(null, rows, 600).contains("旧摘要"))
  }
}
