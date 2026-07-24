package com.diyigemt.arona.communication.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 出向 wire JSON 黄金测试: 消息 DTO (TencentMessage) 和嵌套的 markdown/keyboard 都是 sealed 层次,
// 以基类静态类型直接 encodeToString 会混入 "type":"<FQCN>" 判别字段, 全靠腾讯服务端忽略未知字段
// 才没出事. encodeTencentMessageForWire 关闭了判别字段, 这里把顶层 key 精确集合与嵌套对象的
// 无判别不变量钉死 —— 同时也钉住了子类 @Transient + 基类 @EncodeDefault 的槽位布局: 未来改 DTO
// 结构会先在这里爆炸而不是线上.
//
// 注意: 不做"全 JSON 递归无 type 键"断言 —— keyboard button action 有合法的数值 type 字段.
class TencentMessageWireFormatTest {
  private val json = Json

  private fun wireObjectOf(message: TencentMessage) =
    json.parseToJsonElement(encodeTencentMessageForWire(message)).jsonObject

  @Test
  fun `群纯文本消息的顶层 key 精确集合且无判别字段`() {
    val wire = wireObjectOf(
      TencentMessageBuilder("msg-1").append("hi").build(isPrivateChannel = false)
    )
    assertEquals(
      setOf("content", "image", "markdown", "keyboard", "ark", "msg_id", "event_id", "msg_seq", "msg_type"),
      wire.keys,
    )
    assertEquals("hi", wire["content"]!!.jsonPrimitive.content)
    assertEquals(0, wire["msg_type"]!!.jsonPrimitive.int)
    assertEquals("msg-1", wire["msg_id"]!!.jsonPrimitive.content)
    assertEquals(1, wire["msg_seq"]!!.jsonPrimitive.int)
  }

  @Test
  fun `群 markdown 加 keyboard 消息嵌套对象无判别字段`() {
    val keyboard = tencentCustomKeyboard {
      row { button("hello") }
    }
    val wire = wireObjectOf(
      TencentMessageBuilder("msg-2")
        .append(TencentCustomMarkdown("## title"))
        .append(keyboard)
        .build(isPrivateChannel = false)
    )
    assertEquals(2, wire["msg_type"]!!.jsonPrimitive.int, "markdown/keyboard 消息应是 msg_type=2")
    val markdown = wire["markdown"]!!.jsonObject
    assertFalse("type" in markdown.keys, "嵌套 markdown 不应携带 sealed 判别字段: ${markdown.keys}")
    assertTrue("content" in markdown.keys)
    val kb = wire["keyboard"]!!.jsonObject
    assertFalse("type" in kb.keys, "嵌套 keyboard 不应携带 sealed 判别字段: ${kb.keys}")
  }

  @Test
  fun `群富媒体消息走 msg_type 7 且携带 media file_info`() {
    val wire = wireObjectOf(
      TencentMessageBuilder("msg-3")
        .append(TencentOfflineImage(resourceId = "finfo", resourceUuid = "uuid", ttl = 0L))
        .build(isPrivateChannel = false)
    )
    assertEquals(
      setOf("content", "image", "markdown", "keyboard", "ark", "msg_id", "event_id", "msg_seq", "msg_type", "media"),
      wire.keys,
    )
    assertEquals(7, wire["msg_type"]!!.jsonPrimitive.int)
    assertEquals("finfo", wire["media"]!!.jsonObject["file_info"]!!.jsonPrimitive.content)
  }

  @Test
  fun `频道私信消息无 msg_type 且无判别字段`() {
    // build(isPrivateChannel=true) 产出的是 GuildMember 私信用的 TencentGuildMessage;
    // 普通子频道消息在生产调用中传 false, 不要把本用例误读成公开频道协议的验证.
    val wire = wireObjectOf(
      TencentMessageBuilder("msg-4").append("hi").build(isPrivateChannel = true)
    )
    assertEquals(
      setOf("content", "image", "markdown", "keyboard", "ark", "msg_id", "event_id", "msg_seq"),
      wire.keys,
    )
  }

  @Test
  fun `反证 - 以基类泛型直接编码会混入判别字段`() {
    // 记录 encodeTencentMessageForWire 存在的理由: 默认 Json 对 sealed 基类做多态编码,
    // 顶层出现 "type":"<FQCN>". 若 kotlinx 未来默认行为变化导致本用例失败, 说明 helper 可以退役.
    val message = TencentMessageBuilder("msg-5").append("hi").build(isPrivateChannel = false)
    val wire = json.parseToJsonElement(json.encodeToString(TencentMessage.serializer(), message)).jsonObject
    assertTrue("type" in wire.keys)
  }
}
