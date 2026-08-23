package com.diyigemt.arona.chatbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// P3 纯函数: 运营页编辑请求的归一化.
class StickerEndpointTest {
  @Test
  fun `status 只能是四个终态, 非法整体拒绝而不是静默丢字段`() {
    assertNull(normalizeStickerEdit(StickerEditRequest(status = "analyzing", tags = listOf("猫"))))
    assertNull(normalizeStickerEdit(StickerEditRequest(status = "READY")))
    val edit = assertNotNull(normalizeStickerEdit(StickerEditRequest(status = " hidden ")))
    assertEquals("hidden", edit.status)
    assertNull(edit.tags, "没给的字段保持 null = 不改")
    assertNull(edit.summary)
  }

  @Test
  fun `tags 去空白去重裁剪, 空列表表示清空`() {
    val edit = assertNotNull(normalizeStickerEdit(StickerEditRequest(tags = listOf(" 猫 ", "猫", "  ", "a".repeat(25)) + (1..9).map { "t$it" })))
    assertEquals(listOf("猫", "a".repeat(20), "t1", "t2", "t3", "t4", "t5", "t6"), edit.tags)
    assertEquals(emptyList(), assertNotNull(normalizeStickerEdit(StickerEditRequest(tags = listOf(" ")))).tags)
  }

  @Test
  fun `summary 截到 200 字, 三个字段都没给拒绝`() {
    assertEquals("a".repeat(200), normalizeStickerEdit(StickerEditRequest(summary = " " + "a".repeat(201)))?.summary)
    assertNull(normalizeStickerEdit(StickerEditRequest(id = "x")))
  }
}
