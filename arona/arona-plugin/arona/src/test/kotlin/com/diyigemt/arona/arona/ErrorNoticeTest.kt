package com.diyigemt.arona.arona

import com.diyigemt.arona.communication.TencentApiErrorResp
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageChainBuilder
import com.diyigemt.arona.communication.message.TencentCustomMarkdown
import com.diyigemt.arona.communication.message.TencentGuildImage
import com.diyigemt.arona.communication.message.TencentMessageBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorNoticeTest {
  // ---- extractAronaImageName: 图片元素 ----

  @Test
  fun `从图片元素提取中文文件名`() {
    assertEquals("日服人权", extractAronaImageName(imageChain("${PREFIX}日服人权.png")))
  }

  @Test
  fun `从多级路径提取文件名`() {
    assertEquals("日服人权", extractAronaImageName(imageChain("${PREFIX}some/deeper/日服人权.png")))
  }

  @Test
  fun `多张图片取链中第一张`() {
    val chain = MessageChainBuilder("source-id")
      .append(TencentGuildImage("${PREFIX}first/第一张.png"))
      .append(TencentCustomMarkdown("![img #1px #1px](${PREFIX}second/第二张.png)"))
      .append(TencentGuildImage("${PREFIX}third/第三张.png"))
      .build()
    assertEquals("第一张", extractAronaImageName(chain))
  }

  @Test
  fun `非 arona 图床不命中`() {
    assertNull(extractAronaImageName(imageChain("https://example.com/image/日服人权.png")))
  }

  // ---- extractAronaImageName: markdown ----

  @Test
  fun `从 markdown 图片语法提取文件名`() {
    val chain = markdownChain("说明\n![img #100px #200px](${PREFIX}some/国际服未来视.png)")
    assertEquals("国际服未来视", extractAronaImageName(chain))
  }

  @Test
  fun `markdown 转义括号不会截断 url 匹配`() {
    val chain = markdownChain("![img #100px #200px](${PREFIX}角色%28泳装%29.png)")
    assertEquals("角色%28泳装%29", extractAronaImageName(chain))
  }

  @Test
  fun `placeholder 含右方括号仍命中`() {
    val chain = markdownChain("![角色]泳装 #1px #1px](${PREFIX}some/plana.png)")
    assertEquals("plana", extractAronaImageName(chain))
  }

  @Test
  fun `placeholder 含括号仍命中`() {
    val chain = markdownChain("![アル(泳装) #1px #1px](${PREFIX}aru.png)")
    assertEquals("aru", extractAronaImageName(chain))
  }

  @Test
  fun `图片长宽可省略`() {
    val chain = markdownChain("![只有占位](${PREFIX}some/plana.png)")
    assertEquals("plana", extractAronaImageName(chain))
  }

  @Test
  fun `placeholder 带长宽后缀仍命中`() {
    val chain = markdownChain("![placeholder #1013px #1847px](${PREFIX}some/plana.png)")
    assertEquals("plana", extractAronaImageName(chain))
  }

  @Test
  fun `未闭合 alt 不跨行串联到下一行普通链接`() {
    // alt 不能越过换行, 故 ![未闭合 在本行无 ']' 收尾; 次行是普通链接(无前导 '!'), 整体不应命中.
    val chain = markdownChain("第一行 ![未闭合\n[普通链接](${PREFIX}a/甲.png)")
    assertNull(extractAronaImageName(chain))
  }

  @Test
  fun `同一 markdown 多图取第一张`() {
    val chain = markdownChain("![img](${PREFIX}a/甲.png) ![img](${PREFIX}b/乙.png)")
    assertEquals("甲", extractAronaImageName(chain))
  }

  @Test
  fun `首图 placeholder 含右方括号时仍取首图`() {
    // 同时验证「alt 含 ']'」放宽与「多图取第一张」两条语义: 非贪婪须回溯到首图真正的 '](' 边界.
    val chain = markdownChain("![甲]说明 #1px #1px](${PREFIX}a/甲.png) ![乙](${PREFIX}b/乙.png)")
    assertEquals("甲", extractAronaImageName(chain))
  }

  @Test
  fun `普通 markdown 链接不算图片`() {
    assertNull(extractAronaImageName(markdownChain("[查看图片](${PREFIX}some/日服人权.png)")))
  }

  // ---- extractAronaImageNameFromUrl: 文件名解析 ----

  @Test
  fun `无扩展名时保留原文件名`() {
    assertEquals("日服人权", extractAronaImageNameFromUrl("${PREFIX}some/日服人权"))
  }

  @Test
  fun `仅去掉最后一级扩展名`() {
    assertEquals("foo.tar", extractAronaImageNameFromUrl("${PREFIX}archive/foo.tar.gz"))
  }

  @Test
  fun `提取前去掉查询与锚点`() {
    assertEquals("日服人权", extractAronaImageNameFromUrl("${PREFIX}some/日服人权.png?version=2#preview"))
  }

  @Test
  fun `空文件名返回 null`() {
    assertNull(extractAronaImageNameFromUrl("${PREFIX}.png"))
  }

  // ---- buildErrorNotice ----

  @Test
  fun `违规且命中图片时构造直连提示`() {
    val notice = buildErrorNotice(
      sourceId = "source-id",
      source = errorSource(CONTENT_VIOLATION_CODE, "content violation"),
      sentChain = imageChain("${PREFIX}some/日服人权.png"),
    )
    assertEquals(
      listOf(
        "错误发生，消息内容违规，请直接使用直连查看：",
        "https://tutorial.arona.diyigemt.com/home?name=%E6%97%A5%E6%9C%8D%E4%BA%BA%E6%9D%83",
      ),
      notice.map { it.toString() },
    )
    assertEquals(
      "错误发生，消息内容违规，请直接使用直连查看：\nhttps://tutorial.arona.diyigemt.com/home?name=%E6%97%A5%E6%9C%8D%E4%BA%BA%E6%9D%83",
      wireContent(notice),
    )
  }

  @Test
  fun `非违规错误走通用提示`() {
    val notice = buildErrorNotice(
      sourceId = "source-id",
      source = errorSource(12345, "other error"),
      sentChain = imageChain("${PREFIX}日服人权.png"),
    )
    assertEquals("错误发生\nmessage: other error", wireContent(notice))
  }

  @Test
  fun `违规但提取不到图片名时回退通用提示`() {
    val notice = buildErrorNotice(
      sourceId = "source-id",
      source = errorSource(CONTENT_VIOLATION_CODE, "content violation"),
      sentChain = imageChain("${PREFIX}.png"),
    )
    assertEquals("错误发生\nmessage: content violation", wireContent(notice))
  }

  @Test
  fun `通用提示内容与历史保持一致`() {
    val notice = buildErrorNotice(
      sourceId = "source-id",
      source = errorSource(CONTENT_VIOLATION_CODE, "腾讯返回的错误信息"),
      sentChain = markdownChain("没有匹配图片"),
    )
    assertEquals(listOf("错误发生", "message: 腾讯返回的错误信息"), notice.map { it.toString() })
    assertEquals("错误发生\nmessage: 腾讯返回的错误信息", wireContent(notice))
  }

  private companion object {
    // 复用生产常量, 避免测试侧前缀与实现漂移; 输出文案仍用字面量精确断言。
    const val PREFIX = ARONA_IMAGE_PREFIX
  }

  private fun imageChain(url: String): MessageChain =
    MessageChainBuilder("source-id").append(TencentGuildImage(url)).build()

  private fun markdownChain(content: String): MessageChain =
    MessageChainBuilder("source-id").append(TencentCustomMarkdown(content)).build()

  // 上行实际 content: 复用发送链路的 TencentMessageBuilder, 验证两段 PlainText 由 "\n" 连接成两行。
  private fun wireContent(chain: MessageChain): String =
    TencentMessageBuilder().append(chain).build().content

  private fun errorSource(code: Int, message: String): TencentApiErrorResp =
    TencentApiErrorResp(message = message, code = code, traceId = "trace-id")
}
