package com.diyigemt.arona.communication.message

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 锁住群消息 mentions 字段的反序列化与 is_you 映射:
//  - mentions 用于判定消息是否 @ 了机器人自身 (mentions.any { is_you }), 而非依赖 content 文本.
//  - 除 is_you 外字段全部容错 (可空/默认), 单个 mention 缺字段不得让整条消息 decode 失败.
//  - 旧 payload 无 mentions 字段时 mentions 为 null, 不应抛.
class TencentGroupMessageMentionRawTest {

  // 生产解码器同样开启 ignoreUnknownKeys, 这里对齐以覆盖真实路径.
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `带 mentions 的群消息能解析且 is_you 映射正确`() {
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "msg-1",
        "author": { "member_openid": "u-1" },
        "content": "<@bot> 找猪 -i 1",
        "timestamp": "2026-06-19T18:42:16+08:00",
        "group_openid": "g-1",
        "mentions": [
          {
            "id": "bot-1",
            "username": "星奈",
            "bot": true,
            "member_openid": "bot-1",
            "scope": "single",
            "is_you": true,
            "member_role": "member"
          }
        ]
      }
      """.trimIndent(),
    )
    val mentions = assertNotNull(raw.mentions, "mentions 必须被解析")
    assertEquals(1, mentions.size)
    assertTrue(mentions.single().isYou, "is_you=true 必须映射到 isYou")
    assertTrue(raw.mentions?.any { it.isYou } == true, "判定逻辑应识别出 @ 机器人自身")
  }

  @Test
  fun `mentions 缺非关键字段仍可解析 is_you 缺省为 false`() {
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "msg-2",
        "author": { "member_openid": "u-2" },
        "content": "hi",
        "timestamp": "2026-06-19T18:42:16+08:00",
        "group_openid": "g-2",
        "mentions": [ { "id": "someone" } ]
      }
      """.trimIndent(),
    )
    val mention = assertNotNull(raw.mentions).single()
    assertEquals("someone", mention.id)
    assertNull(mention.username)
    assertTrue(!mention.isYou, "缺 is_you 时缺省为 false")
    assertTrue(raw.mentions?.any { it.isYou } != true, "无 @ 机器人时判定为 false")
  }

  @Test
  fun `旧 payload 无 mentions 字段时为 null 不抛`() {
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "msg-3",
        "author": { "member_openid": "u-3" },
        "content": "plain",
        "timestamp": "2026-06-19T18:42:16+08:00",
        "group_openid": "g-3"
      }
      """.trimIndent(),
    )
    assertNull(raw.mentions, "缺字段时 mentions 应为 null")
  }
}
