package com.diyigemt.arona.communication.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Sprint 3.5(b) 锁住 TencentMessageRaw.toMessageChain 对 Unicode 空白的容忍:
//  - Java/Kotlin 默认 \s 与 String.trim() / Char.isWhitespace 都不覆盖 NBSP (U+00A0) 与全角空格 (U+3000).
//  - 客户端经常把这两类粘进 @bot 消息, 旧 split(" ") 把整段当一个 token, at 解析失败.
//  - 改用显式字符集 [\\s\\u00A0\\u3000]+ 后, 这些场景能正确切出 at + 余下文本.
class TencentMessageRawWhitespaceTest {

  private fun rawWith(content: String): TencentMessageRaw =
    TencentFriendMessageRaw(
      id = "msg-1",
      author = TencentFriendMessageAuthorRaw(userOpenid = "u-1"),
      content = content,
      timestamp = "2026-04-25T00:00:00Z",
    )

  @Test
  fun `at 后跟普通空格能解析出 At + PlainText`() {
    val chain = rawWith("<@!12345> hello world").toMessageChain()
    val at = chain.firstOrNull { it is TencentAt } as? TencentAt
    assertNotNull(at, "ASCII 空格分隔时 at 必须能解析")
    assertEquals("12345", at.target)
    val text = chain.firstOrNull { it is PlainText } as? PlainText
    assertNotNull(text)
    assertEquals("hello world", text.toString(), "余下文本归一化为空格")
  }

  @Test
  fun `at 后跟 NBSP 仍然可解析`() {
    // U+00A0 是 Tencent 客户端在 at 后偶尔会插入的 non-breaking space.
    val chain = rawWith("<@!67890> hi").toMessageChain()
    val at = chain.firstOrNull { it is TencentAt } as? TencentAt
    assertNotNull(at, "NBSP 分隔时 at 也得能解析, 旧实现 split(\" \") 会失败")
    assertEquals("67890", at.target)
    val text = chain.firstOrNull { it is PlainText } as? PlainText
    assertNotNull(text)
    assertEquals("hi", text.toString())
  }

  @Test
  fun `at 后跟全角空格仍然可解析`() {
    val chain = rawWith("<@!42>　全角空格").toMessageChain()
    val at = chain.firstOrNull { it is TencentAt } as? TencentAt
    assertNotNull(at, "全角空格分隔时 at 也得能解析")
    assertEquals("42", at.target)
    val text = chain.firstOrNull { it is PlainText } as? PlainText
    assertNotNull(text)
    assertEquals("全角空格", text.toString())
  }

  @Test
  fun `连续多个 ASCII 空格不影响 at 解析 余下文本归一化`() {
    val chain = rawWith("<@!1>   foo   bar").toMessageChain()
    val at = chain.firstOrNull { it is TencentAt } as? TencentAt
    assertNotNull(at)
    assertEquals("1", at.target)
    val text = chain.firstOrNull { it is PlainText } as? PlainText
    assertNotNull(text)
    // split(Regex("[\\s\\u00A0\\u3000]+")) 拆出非空 token, joinToString(" ") 单空格连回.
    assertEquals("foo bar", text.toString(), "多 ASCII 空格被归一化")
  }

  @Test
  fun `前导 NBSP 应被显式 trim 不污染 at 解析`() {
    // 旧 trim() 默认按 isWhitespace 处理, NBSP 不会被吃; 新 trim 用 isTencentMessageWhitespace.
    val chain = rawWith(" <@!9> body").toMessageChain()
    val at = chain.firstOrNull { it is TencentAt } as? TencentAt
    assertNotNull(at, "前导 NBSP trim 失效则 first()=NBSP+at, at 解析必失败")
    assertEquals("9", at.target)
  }

  @Test
  fun `没有 at 时纯文本回到 PlainText 不抛`() {
    val chain = rawWith("just plain text").toMessageChain()
    assertEquals(1, chain.size)
    assertTrue(chain.first() is PlainText)
    assertEquals("just plain text", (chain.first() as PlainText).toString())
  }

  @Test
  fun `空 content 不产生 PlainText`() {
    val chain = rawWith("   　 ").toMessageChain()
    // 全空白 trim 后 isBlank, 不进 split 分支, 不加任何 PlainText.
    assertEquals(0, chain.size)
  }
}
