package com.diyigemt.arona.communication.message

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 回归保护:
// - MessageChainImpl.serialization 旧版返回 ""; 新版按元素 serialization() 拼接, 不加分隔符.
// - MessageChainAsStringSerializer 旧版两个方法都是 TODO(); 新版做纯文本 (lossy) 往返, 和 serialization() 配套.
class MessageChainSerializationTest {

  @Test
  fun `MessageChainImpl serialization 逐元素调用 serialization 并以空串拼接`() {
    val chain = MessageChainBuilder(
      PlainText("hello"),
      PlainText(" "),
      PlainText("world"),
    ).build()
    assertEquals("hello world", chain.serialization())
  }

  @Test
  fun `MessageChainImpl serialization 和 toString 的分隔符不同`() {
    // toString 带空格便于人读; serialization 不加分隔符保留消息边界语义.
    val a = PlainText("a")
    val b = PlainText("b")
    val chain = MessageChainBuilder(a, b).build()
    assertEquals("a b", chain.toString(), "toString 用空格分隔便于可读")
    assertEquals("ab", chain.serialization(), "serialization 不加额外字符")
  }

  @Test
  fun `MessageChainImpl serialization 空链返回空串`() {
    val chain = MessageChainBuilder().build()
    assertEquals("", chain.serialization())
  }

  @Test
  fun `MessageChainAsStringSerializer 纯文本 chain 可完整往返`() {
    val json = Json { ignoreUnknownKeys = true }
    val chain = PlainText("hello world").toMessageChain()

    val encoded = json.encodeToString(MessageChainAsStringSerializer, chain)
    assertEquals("\"hello world\"", encoded, "encode 走 value.serialization() → encodeString")

    val decoded = json.decodeFromString(MessageChainAsStringSerializer, encoded)
    assertEquals("hello world", decoded.serialization())
    assertEquals(1, decoded.size, "decode 后应为单元素 chain")
    assertTrue(decoded.first() is PlainText, "decode 后元素类型为 PlainText (lossy)")
  }

  @Test
  fun `MessageChainAsStringSerializer 结构消息 round trip 时只保留文本信息`() {
    // 这是已知的 lossy 约束: 结构消息 (At/image/markdown) 序列化后只剩文本表示.
    val json = Json { ignoreUnknownKeys = true }
    val original = MessageChainBuilder(
      PlainText("prefix "),
      PlainText("suffix"),
    ).build()

    val encoded = json.encodeToString(MessageChainAsStringSerializer, original)
    val decoded = json.decodeFromString(MessageChainAsStringSerializer, encoded)

    assertEquals(original.serialization(), decoded.serialization(), "serialization 文本形式必须一致")
    // 反序列化只保证单 PlainText 持有完整文本, 不还原成多元素 chain.
    assertEquals(1, decoded.size)
  }
}
