package com.diyigemt.arona.chatbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekParsingTest {
  @Test
  fun `容错解析 - 围栏与前后杂文`() {
    val action = assertNotNull(parseBotAction("好的:\n```json\n{\"reply\": \"喵~\", \"silent\": false}\n```"))
    assertEquals("喵~", action.reply)
    assertNotNull(parseBotAction("{reply: \"x\"}")).let { assertEquals("x", it.reply) }
  }

  @Test
  fun `非 JSON 返回 null`() {
    assertNull(parseBotAction("我不想用 JSON"))
    assertNull(parseBotAction("{ broken"))
  }

  @Test
  fun `sentinel - silent 与 reply 缺失`() {
    assertTrue(assertNotNull(parseBotAction("{\"silent\": true}")).silent)
    assertNull(assertNotNull(parseBotAction("{}")).reply)
  }

  @Test
  fun `JSON_EMPTY 与 MODEL_SILENT 必须区分`() {
    assertEquals(LlmOutcome.Noop(NoopReason.JSON_EMPTY), DeepSeekClient.classify("   "))
    assertEquals(LlmOutcome.Noop(NoopReason.MODEL_SILENT), DeepSeekClient.classify("{\"silent\": true}"))
    assertEquals(LlmOutcome.Noop(NoopReason.MODEL_SILENT), DeepSeekClient.classify("{\"reply\": \"  \"}"))
    assertEquals(NoopReason.JSON_INVALID, (DeepSeekClient.classify("纯文本") as LlmOutcome.Noop).reason)
    assertEquals(LlmOutcome.Reply("喵"), DeepSeekClient.classify("{\"reply\": \" 喵 \"}"))
  }

  @Test
  fun `响应解析带 usage prompt_tokens, 缺失为 null`() {
    val withUsage = DeepSeekClient.extractContent("""{"choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":321,"completion_tokens":5}}""")
    assertEquals(DeepSeekClient.Response.Content("hi", 321), withUsage)
    val noUsage = DeepSeekClient.extractContent("""{"choices":[{"message":{"content":"hi"}}]}""")
    assertEquals(DeepSeekClient.Response.Content("hi", null), noUsage)
    assertTrue(DeepSeekClient.extractContent("""{"error":{"message":"boom"}}""") is DeepSeekClient.Response.Error)
    assertEquals(321, (DeepSeekClient.classify("{\"reply\": \"喵\"}", 321) as LlmOutcome.Reply).promptTokens)
  }

  @Test
  fun `摘要进入 user prompt 最前面, 空摘要等价无摘要`() {
    val prompt = buildUserPrompt(emptyList(), "小红", "你好", summary = "小明爱吃鱼")
    assertTrue(prompt.startsWith("更早的聊天摘要"))
    assertTrue(prompt.contains("小明爱吃鱼"))
    assertTrue(prompt.endsWith("现在 小红 说: 你好"))
    assertEquals("现在 小红 说: 你好", buildUserPrompt(emptyList(), "小红", "你好", summary = "  "))
  }

  @Test
  fun `prompt 装配 - bot 自己的话标为我 空历史只有当前句`() {
    val history = listOf(
      ChatLine("1", "g", "u1", "小明", "今天吃啥", fromBot = false, ts = java.util.Date(0)),
      ChatLine("2", "g", BOT_SENDER_ID, null, "吃鱼喵", fromBot = true, ts = java.util.Date(0)),
    )
    val prompt = buildUserPrompt(history, "小红", "我也想吃")
    assertTrue(prompt.contains("小明: 今天吃啥"))
    assertTrue(prompt.contains("我: 吃鱼喵"))
    assertTrue(prompt.endsWith("现在 小红 说: 我也想吃"))
    assertEquals("现在 小红 说: 你好", buildUserPrompt(emptyList(), "小红", "你好"))
  }

  @Test
  fun `引用原文进入 prompt 且按 history 判定是否 bot 所说`() {
    val history = listOf(ChatLine("1", "g", BOT_SENDER_ID, null, "在喵～", fromBot = true, ts = java.util.Date(0)))
    val mine = buildUserPrompt(history, "小红", "你能做些什么", quoted = "在喵～")
    assertTrue(mine.contains("对方引用了我之前说的话"))
    assertTrue(mine.contains("「在喵～」"))
    assertTrue(mine.endsWith("现在 小红 说: 你能做些什么"))

    val other = buildUserPrompt(history, "小红", "真的吗", quoted = "今天吃鱼")
    assertTrue(other.contains("对方引用了群里的一条消息"))
    assertTrue(other.contains("「今天吃鱼」"))

    // 空白引用与无引用等价.
    assertEquals("现在 小红 说: hi", buildUserPrompt(emptyList(), "小红", "hi", quoted = "  "))
  }
}
