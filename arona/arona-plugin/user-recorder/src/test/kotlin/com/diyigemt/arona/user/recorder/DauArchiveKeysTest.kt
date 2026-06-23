package com.diyigemt.arona.user.recorder

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// 锁定归档的两条纯逻辑边界:
//  1. 仅早于 today 的合法按天 key 才被识别为可归档; 累计键 / 当天 / 未来 / 非法日期 / 未知后缀全部排除。
//  2. 计数 hash (field -> value) 解析为 field -> Long, 非数字值必须抛错而非静默丢数。
class DauArchiveKeysTest {
  private val today = LocalDate.parse("2026-06-22")

  @Test
  fun `识别早于今天的合法按天 key`() {
    assertEquals(LocalDate.parse("2026-06-21"), DauArchiveKeys.parseArchivableDate("dau.2026-06-21.dau", today))
    assertEquals(LocalDate.parse("2024-01-01"), DauArchiveKeys.parseArchivableDate("dau.2024-01-01.contact", today))
    assertEquals(LocalDate.parse("2025-12-31"), DauArchiveKeys.parseArchivableDate("dau.2025-12-31.message", today))
    assertEquals(LocalDate.parse("2026-06-20"), DauArchiveKeys.parseArchivableDate("dau.2026-06-20.command", today))
  }

  @Test
  fun `排除累计键_当天_未来_非法日期与未知后缀`() {
    // 累计键 (永不归档)
    assertNull(DauArchiveKeys.parseArchivableDate(UserKey, today))
    assertNull(DauArchiveKeys.parseArchivableDate(ContactKey, today))
    assertNull(DauArchiveKeys.parseArchivableDate(CommandKey, today))
    // 当天与未来
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2026-06-22.dau", today))
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2026-06-23.dau", today))
    // 非法日历日期
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2025-02-30.dau", today))
    // 未知后缀 / 多余分段 / 噪声
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2026-06-21.unknown", today))
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2026-06-21.dau.extra", today))
    assertNull(DauArchiveKeys.parseArchivableDate("dau.2026-6-1.dau", today))
    assertNull(DauArchiveKeys.parseArchivableDate("other.2026-06-21.dau", today))
  }

  @Test
  fun `dailyKeys 覆盖四个按天 key`() {
    assertEquals(
      listOf("dau.2026-06-21.dau", "dau.2026-06-21.contact", "dau.2026-06-21.message", "dau.2026-06-21.command"),
      DauArchiveKeys.dailyKeys("2026-06-21").toList(),
    )
  }

  @Test
  fun `decodeCountHash 解析计数 hash`() {
    assertEquals(mapOf("gacha" to 3L, "dau" to 1L), decodeCountHash(mapOf("gacha" to "3", "dau" to "1")))
    assertEquals(emptyMap<String, Long>(), decodeCountHash(emptyMap()))
  }

  @Test
  fun `decodeCountHash 非数字值抛错`() {
    assertFailsWith<IllegalStateException> { decodeCountHash(mapOf("gacha" to "x")) }
  }
}
