package com.diyigemt.arona.chatbot

import com.diyigemt.arona.webui.pluginconfig.PluginConfigCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SegmenterTest {
  @Test
  fun `括号-书名号-引号内部不切`() {
    assertEquals(listOf("开头（里面。不要切）结尾", "下一句"), Segmenter.split("开头（里面。不要切）结尾。下一句。", 4))
    assertEquals(listOf("参考《标题。副标题》结束", "下一句"), Segmenter.split("参考《标题。副标题》结束。下一句。", 4))
    assertEquals(listOf("他说“里面？别切”然后结束", "下一句"), Segmenter.split("他说“里面？别切”然后结束。下一句。", 4))
    assertEquals(listOf("don't stop", "下一句"), Segmenter.split("don't stop。下一句。", 4))
    // 中文旁的直引号是配对引号, 不是撇号: 内部不切.
    assertEquals(listOf("他说'里面。别切'结束", "下一句"), Segmenter.split("他说'里面。别切'结束。下一句。", 4))
  }

  @Test
  fun `颜文字占位保护内部切分符`() {
    assertEquals(listOf("(；´д｀)ゞ", "下一句"), Segmenter.split("(；´д｀)ゞ。下一句。", 4))
    assertEquals(listOf("好耶～～", "下一句"), Segmenter.split("好耶～～。下一句。", 4))
  }

  @Test
  fun `最大段数受限且同优先级时靠近均分目标`() {
    assertEquals(listOf("甲甲甲。乙", "丙丙丙。丁"), Segmenter.split("甲甲甲。乙。丙丙丙。丁。", 2))
    assertTrue(Segmenter.split("一。二。三。四。五。六。", 4).size <= 4)
  }

  @Test
  fun `换行切点优先级高于长度距离`() {
    assertEquals(listOf("甲甲甲。乙乙乙", "丙。丁"), Segmenter.split("甲甲甲。乙乙乙\n丙。丁", 2))
  }

  @Test
  fun `段尾只剥离句号逗号分号并保留语气标点`() {
    assertEquals(listOf("第一句", "第二句", "第三句"), Segmenter.split("第一句，\n第二句；第三句。", 4))
    assertEquals(listOf("真的？", "好耶～", "等等…"), Segmenter.split("真的？好耶～等等…", 4))
  }

  @Test
  fun `后处理丢弃空段`() {
    assertEquals(listOf("第一句", "第二句"), Segmenter.split("。\n第一句。\n。第二句。", 4))
    assertEquals(emptyList(), Segmenter.split(" \n ", 3))
  }

  @Test
  fun `delay 中日韩计完整时长其它减半并 clamp`() {
    assertEquals(0, Segmenter.delayMillis("", 300, 500, 3_000))
    assertEquals(500, Segmenter.delayMillis("中", 300, 500, 3_000))
    assertEquals(600, Segmenter.delayMillis("abcd", 300, 500, 3_000))
    assertEquals(750, Segmenter.delayMillis("あ한A", 300, 500, 3_000))
    assertEquals(3_000, Segmenter.delayMillis("中".repeat(20), 300, 500, 3_000))
    // 配置错值纠偏, 不抛异常.
    assertEquals(0, Segmenter.delayMillis("中", -1, -1, -1))
  }

  @Test
  fun `单段原样直通`() {
    val text = "  只有一段。  "
    assertEquals(listOf(text), Segmenter.split(text, 3))
  }

  @Test
  fun `群配置最大段数限制为 2 到 4`() {
    assertIs<PluginConfigCheckResult.PluginConfigCheckAccept>(ChatbotConfig(segmentMaxCount = 2).check())
    assertIs<PluginConfigCheckResult.PluginConfigCheckAccept>(ChatbotConfig(segmentMaxCount = 4).check())
    assertIs<PluginConfigCheckResult.PluginConfigCheckReject>(ChatbotConfig(segmentMaxCount = 1).check())
    assertIs<PluginConfigCheckResult.PluginConfigCheckReject>(ChatbotConfig(segmentMaxCount = 5).check())
  }
}
