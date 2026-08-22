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
}
