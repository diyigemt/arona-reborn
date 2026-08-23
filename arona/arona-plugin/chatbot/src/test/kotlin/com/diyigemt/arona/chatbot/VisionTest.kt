package com.diyigemt.arona.chatbot

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// P2 纯函数: 视觉请求体、表情打标解析、配图关键词、粗排选图、带图/图片描述的 prompt.
class VisionTest {
  private val image = DownloadedImage(byteArrayOf(1, 2, 3), "image/png")

  @Test
  fun `带图请求 - user content 为数组, 不带 response_format`() {
    val body = DeepSeekClient.buildRequestBody("m", "sys", "看图", jsonMode = false, images = listOf(image, image))
    val user = body["messages"]!!.jsonArray[1].jsonObject
    val content = user["content"]!!.jsonArray
    assertEquals(3, content.size)
    assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("看图", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("data:image/png;base64,AQID", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    assertNull(body["response_format"])
  }

  @Test
  fun `纯文本请求 - content 仍是字符串, 带 response_format`() {
    val body = DeepSeekClient.buildRequestBody("m", "sys", "hi", jsonMode = true, images = emptyList())
    assertEquals("hi", body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `表情打标解析 - 围栏容错, 缺字段取保守默认`() {
    val a = assertNotNull(parseStickerAnalysis("```json\n{\"is_meme\": true, \"summary\": \"一只猫翻白眼\", \"tags\": [\"无语\", \"猫\"], \"nsfw_risk\": \"low\"}\n```"))
    assertTrue(a.isMeme)
    assertEquals(listOf("无语", "猫"), a.tags)
    assertEquals("low", a.nsfwRisk)
    val bare = assertNotNull(parseStickerAnalysis("{}"))
    assertFalse(bare.isMeme, "缺 is_meme 按不是表情")
    assertEquals("high", bare.nsfwRisk, "缺 nsfw_risk 按最高风险")
    assertNull(parseStickerAnalysis("不是 JSON"))
  }

  @Test
  fun `回复里的 sticker 关键词随 Reply 透出, 空白视为无`() {
    assertEquals("无语 猫", (DeepSeekClient.classify("{\"reply\": \"喵\", \"sticker\": \" 无语 猫 \"}") as LlmOutcome.Reply).sticker)
    assertNull((DeepSeekClient.classify("{\"reply\": \"喵\", \"sticker\": \"  \"}") as LlmOutcome.Reply).sticker)
    assertNull((DeepSeekClient.classify("{\"reply\": \"喵\"}") as LlmOutcome.Reply).sticker)
  }

  @Test
  fun `粗排选图 - tag 命中权重高于 summary, 无命中为 null, 前五随机`() {
    val cat = StickerCandidate("cat", listOf("无语", "猫"), "一只猫翻白眼", "k1")
    val dog = StickerCandidate("dog", listOf("开心"), "狗狗无语地看着你", "k2")
    val none = StickerCandidate("none", listOf("睡觉"), "打哈欠", "k3")
    assertEquals("cat", pickSticker(listOf(dog, cat, none), "无语 翻白眼") { 0.0 }?.id)
    assertEquals("cat", pickSticker(listOf(dog, cat, none), "无语") { 0.0 }?.id, "tag 3 分 > summary 1 分")
    assertEquals("dog", pickSticker(listOf(dog, cat, none), "无语") { 0.99 }?.id, "前几名里随机")
    assertNull(pickSticker(listOf(dog, cat, none), "飞机, 火车"))
    assertNull(pickSticker(listOf(cat), "  ") { 0.0 })
    assertNull(pickSticker(emptyList(), "猫") { 0.0 })
  }

  @Test
  fun `prompt - 附图说明与 history 里的图片描述`() {
    assertEquals("现在 小红 发了 1 张图片 (见附图)", buildUserPrompt(emptyList(), "小红", PluginMain.IMAGE_PLACEHOLDER, imageCount = 1))
    assertEquals("现在 小红 说: 看这个 (附 2 张图, 见附图)", buildUserPrompt(emptyList(), "小红", "看这个", imageCount = 2))
    assertEquals("现在 小红 说: 看这个", buildUserPrompt(emptyList(), "小红", "看这个", imageCount = 0), "下载全失败退化为纯文本")

    val line = ChatLine("1", "g", "u", "小明", "[图片]", fromBot = false, ts = Date(0), imageSummary = "一只猫翻白眼")
    assertEquals("[图片] (图片内容: 一只猫翻白眼)", line.promptText)
    assertTrue(buildUserPrompt(listOf(line), "小红", "哈哈").contains("小明: [图片] (图片内容: 一只猫翻白眼)"))
    assertEquals("hi", ChatLine("1", "g", "u", null, "hi", false, Date(0), imageSummary = " ").promptText)
    assertTrue(buildSummaryPrompt(null, listOf(line), 600).contains("小明: [图片] (图片内容: 一只猫翻白眼)"), "图片描述也要进长期记忆")
  }

  private fun pickSticker(candidates: List<StickerCandidate>, query: String) = pickSticker(candidates, query) { 0.0 }
}
