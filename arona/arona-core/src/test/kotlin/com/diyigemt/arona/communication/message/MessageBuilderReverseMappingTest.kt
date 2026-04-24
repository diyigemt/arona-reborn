package com.diyigemt.arona.communication.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 锁 Sprint 2.3 builder 反向映射契约:
//  - TencentMessageBuilder.append(TencentGuildMessage / TencentGroupMessage / TencentMessage) 把
//    收到的 Tencent 消息展开为链上 Message 元素, 同时把 messageId/eventId 吸收到 builder 的 source.
//  - MessageChainBuilder.append(TencentMessage) 做同样的反向展开, 产出 MessageChain.
//  - append(other: TencentMessageBuilder) 不再 TODO, 委托 build() 再分派.
class MessageBuilderReverseMappingTest {

  @Test
  fun `TencentMessageBuilder append TencentGuildMessage 展开 content image markdown`() {
    val src = TencentGuildMessage(
      content = "hi",
      image = "https://img.example.com/a.png",
      markdown = TencentCustomMarkdown("## h"),
      messageId = "src-msg-1",
      eventId = "evt-1",
    )
    val built = TencentMessageBuilder().append(src).build(isPrivateChannel = true)
    require(built is TencentGuildMessage)
    assertEquals("hi", built.content, "content 反向展开为 PlainText 后再 build 回字符串")
    assertEquals("https://img.example.com/a.png", built.image, "image URL 走 TencentGuildImage 再 build 回 URL")
    assertTrue(built.markdown is TencentCustomMarkdown, "markdown 结构保留")
    assertEquals("src-msg-1", built.messageId, "messageId 被 absorb 进 builder source")
    assertEquals("evt-1", built.eventId, "eventId 同上")
  }

  @Test
  fun `TencentMessageBuilder append TencentGroupMessage PLAIN_TEXT 只追加 PlainText`() {
    val src = TencentGroupMessage(
      content = "hello",
      messageType = TencentMessageType.PLAIN_TEXT,
      messageId = "src-msg-2",
    )
    val built = TencentMessageBuilder().append(src).build()
    require(built is TencentGroupMessage)
    assertEquals(TencentMessageType.PLAIN_TEXT, built.messageType)
    assertEquals("hello", built.content)
    assertNull(built.image)
    assertNull(built.markdown)
    assertEquals("src-msg-2", built.messageId)
  }

  @Test
  fun `TencentMessageBuilder append TencentGroupMessage IMAGE 反向得到 TencentGuildImage`() {
    val src = TencentGroupMessage(
      content = "",
      messageType = TencentMessageType.IMAGE,
      image = "https://i.example/b.png",
    )
    val built = TencentMessageBuilder().append(src).build()
    require(built is TencentGroupMessage)
    assertEquals(TencentMessageType.IMAGE, built.messageType, "IMAGE 反向再 build 仍是 IMAGE")
    assertEquals("https://i.example/b.png", built.image, "URL 被保留")
  }

  @Test
  fun `TencentMessageBuilder append TencentGroupMessage MARKDOWN 反向保留 markdown`() {
    val src = TencentGroupMessage(
      content = "",
      messageType = TencentMessageType.MARKDOWN,
      markdown = TencentCustomMarkdown("## t"),
    )
    val built = TencentMessageBuilder().append(src).build()
    require(built is TencentGroupMessage)
    assertEquals(TencentMessageType.MARKDOWN, built.messageType)
    assertTrue(built.markdown is TencentCustomMarkdown)
  }

  @Test
  fun `TencentMessageBuilder append TencentGroupMessage FILE 不伪造 image`() {
    // FILE 类型的消息只有 media.fileInfo, 没有可逆的 URL. 反向映射应跳过, 避免后续 build 误发 IMAGE.
    val src = TencentGroupMessage(
      content = "",
      messageType = TencentMessageType.FILE,
      media = TencentMessageMediaInfo(fileInfo = "fi-123"),
    )
    val built = TencentMessageBuilder().append(src).build()
    require(built is TencentGroupMessage)
    // 原容器没有 image/markdown, 反向也不该造出, 最终 build 的 messageType 落回 PLAIN_TEXT 默认值.
    assertEquals(TencentMessageType.PLAIN_TEXT, built.messageType)
    assertNull(built.image)
  }

  @Test
  fun `TencentMessageBuilder append TencentMessage 分派到子类版本`() {
    val src: TencentMessage = TencentGroupMessage(
      content = "poly",
      messageType = TencentMessageType.PLAIN_TEXT,
      messageId = "poly-src",
    )
    val built = TencentMessageBuilder().append(src).build()
    require(built is TencentGroupMessage)
    assertEquals("poly", built.content)
    assertEquals("poly-src", built.messageId)
  }

  @Test
  fun `TencentMessageBuilder append other TencentMessageBuilder 合并元素`() {
    val first = TencentMessageBuilder("first-src")
      .append(PlainText("a"))
    val second = TencentMessageBuilder("second-src")
      .append(PlainText("b"))
      .append(first)
    val built = second.build()
    require(built is TencentGroupMessage)
    // 两个 PlainText 合并后 build() 把 PlainText 用换行连接.
    assertEquals("b\na", built.content)
  }

  @Test
  fun `MessageChainBuilder append TencentGroupMessage IMAGE 反向得到 TencentGuildImage 元素`() {
    val src = TencentGroupMessage(
      content = "",
      messageType = TencentMessageType.IMAGE,
      image = "https://cdn.example/c.png",
    )
    val chain = MessageChainBuilder().append(src).build()
    assertEquals(1, chain.size)
    val el = chain.first()
    assertTrue(el is TencentGuildImage, "IMAGE 反向展开为 TencentGuildImage")
    assertEquals("https://cdn.example/c.png", el.url)
  }

  @Test
  fun `MessageChainBuilder append TencentGroupMessage PLAIN_TEXT 仅保留 PlainText`() {
    val src = TencentGroupMessage(
      content = "hello",
      messageType = TencentMessageType.PLAIN_TEXT,
      messageId = "chain-src-1",
      eventId = "chain-evt-1",
    )
    val chain = MessageChainBuilder().append(src).build()
    assertEquals(1, chain.size)
    assertTrue(chain.first() is PlainText)
    assertEquals("chain-src-1", chain.sourceId, "sourceMessageId 被 absorb")
    assertEquals("chain-evt-1", chain.eventId)
  }

  @Test
  fun `MessageChainBuilder append TencentGuildMessage 同时 content image markdown 三元素`() {
    val src = TencentGuildMessage(
      content = "guild-text",
      image = "https://g.example/x.png",
      markdown = TencentCustomMarkdown("md body"),
    )
    val chain = MessageChainBuilder().append(src).build()
    // PlainText + TencentGuildImage + TencentCustomMarkdown
    assertEquals(3, chain.size)
    val types = chain.map { it::class }
    assertTrue(types[0] == PlainText::class)
    assertTrue(types[1] == TencentGuildImage::class)
    assertTrue(types[2] == TencentCustomMarkdown::class)
  }

  @Test
  fun `MessageChainBuilder append TencentMessageBuilder 目前是 lossy 的 (走 build 再反向展开)`() {
    // 记录当前已知的损耗语义: TencentMessageBuilder 的多个 PlainText 会被 build() 用 \n 合成一个
    // content, 结构元素 (image/markdown) 也只保留最后一个. MessageChainBuilder.append(other) 通过
    // `append(other.build())` 委托, 会带上这层损耗. 测试把行为锁死, 将来若改成无损合并请同步更新.
    val builder = TencentMessageBuilder("merge-src")
      .append(PlainText("a"))
      .append(PlainText("b"))
    val chain = MessageChainBuilder().append(builder).build()
    // build() 把两个 PlainText 合成 "a\nb" 后反向展开只得到单个 PlainText.
    assertEquals(1, chain.size, "多个 PlainText 被 build 合并, 反向后只剩 1 个")
    assertTrue(chain.first() is PlainText)
    assertEquals("a\nb", (chain.first() as PlainText).toString(), "合并后的文本用 \\n 连接")
  }

  @Test
  fun `MessageChainBuilder append TencentMessage 不触达 FILE ARK EMBED 伪造分支`() {
    val src = TencentGroupMessage(
      content = "",
      messageType = TencentMessageType.ARK,
    )
    val chain = MessageChainBuilder().append(src).build()
    assertEquals(0, chain.size, "ARK 当前无对应 Message 表达, 跳过不追加")
  }
}
