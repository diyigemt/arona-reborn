package com.diyigemt.arona.communication.message

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 锁 Sprint 2.3 markdown serialization 格式契约:
//  - 旧实现: TODO() → 触发就炸. 新实现走 "[tencent:markdown:custom:\"...json-escaped...\"]"
//    / "[tencent:markdown:template:{...json}]". 转义交给 kotlinx.serialization.
//  - 约定 lossy: serialization 输出可 round-trip 为 PlainText 但不还原回结构类型.
class MarkdownSerializationTest {

  @Test
  fun `TencentCustomMarkdown serialization 用 bracket + JSON 字符串 格式`() {
    val md = TencentCustomMarkdown("## hello\nbody")
    val result = md.serialization()
    assertTrue(result.startsWith("[tencent:markdown:custom:"), "前缀锁 bracket 格式")
    assertTrue(result.endsWith("]"), "以 ] 收尾")
    // JSON 字符串把 \n 转义成字面 \\n, 确保 serialization 不会把换行带到 bracket 外面.
    assertFalse(result.contains("\n"), "内容里的换行必须被 JSON 转义, 不能带裸换行")
    assertTrue(result.contains("\\n"), "换行应转义为 \\n")
  }

  @Test
  fun `TencentCustomMarkdown serialization 可被 JSON 解析回内容字符串`() {
    val original = "line1\nline2 with \"quote\" and ]bracket"
    val md = TencentCustomMarkdown(original)
    val wrapped = md.serialization()
    val jsonPart = wrapped
      .removePrefix("[tencent:markdown:custom:")
      .removeSuffix("]")
    val parsed = Json.decodeFromString(String.serializer(), jsonPart)
    assertEquals(original, parsed, "JSON 解码后回到原始内容")
  }

  @Test
  fun `TencentCustomMarkdown serialization 不再抛 NotImplementedError`() {
    val md = TencentCustomMarkdown("foo")
    // 旧行为 TODO() = NotImplementedError. 这条主要锁"不炸".
    md.serialization()
  }

  @Test
  fun `TencentTemplateMarkdown serialization 用 bracket + JSON 对象 格式`() {
    val md = TencentTemplateMarkdown(
      id = "tpl_42",
      params = listOf(
        TencentMarkdownParam("k1", listOf("v1", "v2")),
        TencentMarkdownParam("k2", listOf("v3")),
      ),
    )
    val result = md.serialization()
    assertTrue(result.startsWith("[tencent:markdown:template:"), "前缀锁 bracket 格式")
    assertTrue(result.endsWith("]"), "以 ] 收尾")
    val jsonPart = result
      .removePrefix("[tencent:markdown:template:")
      .removeSuffix("]")
    val roundTrip = Json.decodeFromString(TencentTemplateMarkdown.serializer(), jsonPart)
    assertEquals("tpl_42", roundTrip.id)
    assertEquals(2, roundTrip.params.size)
    assertEquals("k1", roundTrip.params[0].key)
    assertEquals(listOf("v1", "v2"), roundTrip.params[0].values)
  }

  @Test
  fun `TencentTemplateMarkdown 反证 旧 TODO 实现会炸`() {
    // 保留一条行为性断言: 空 params 依然可序列化不抛.
    val md = TencentTemplateMarkdown(id = "x", params = emptyList())
    // 若回退到 TODO() 实现, 这里会抛 NotImplementedError.
    md.serialization()
  }

  @Test
  fun `包含 markdown 的 chain 经 MessageChainAsStringSerializer round trip 只剩 PlainText 包着 bracket 内容`() {
    // 已知 lossy 契约: MessageChainAsStringSerializer 不还原结构类型, markdown 反序列化后只得到 PlainText.
    // 锁死这条行为, 防止未来"顺手"改回结构还原时破坏 serializer 公共约定.
    val json = Json { ignoreUnknownKeys = true }
    val chain = MessageChainBuilder(
      TencentCustomMarkdown("abc"),
    ).build()
    val encoded = json.encodeToString(MessageChainAsStringSerializer, chain)
    val decoded = json.decodeFromString(MessageChainAsStringSerializer, encoded)
    assertEquals(1, decoded.size, "结构类型不还原, 单元素 PlainText")
    assertTrue(decoded.first() is PlainText)
    assertEquals(chain.serialization(), decoded.serialization(), "文本表示稳定")
  }

  @Test
  fun `MessageChain 中包含 markdown 时 serialization 拼接两段 bracket`() {
    val chain = MessageChainBuilder(
      PlainText("prefix "),
      TencentCustomMarkdown("body"),
    ).build()
    val result = chain.serialization()
    assertTrue(result.startsWith("prefix "), "前缀 PlainText 原样保留")
    assertTrue(result.contains("[tencent:markdown:custom:"), "markdown 段走 bracket 前缀")
  }
}
