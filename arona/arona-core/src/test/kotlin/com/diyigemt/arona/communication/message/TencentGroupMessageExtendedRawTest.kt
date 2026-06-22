package com.diyigemt.arona.communication.message

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 锁住平台扩展后的群消息字段反序列化:
//  - author 新增 username/bot/member_role/union_openid, 但群成员身份仍以 member_openid 为准 (author.id).
//  - 新增 group_id(冗余)/message_scene/message_type, 全部可空/默认, 不改变 group_openid 身份语义.
//  - 旧 payload 缺这些字段时不得抛, 新字段缺省为 null/empty.
class TencentGroupMessageExtendedRawTest {

  // 生产解码器同样开启 ignoreUnknownKeys, 这里对齐以覆盖真实路径.
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `平台扩展群消息完整解析新字段`() {
    // 故意让 author.id 字段与 member_openid 取不同值, group_id 与 group_openid 取不同值,
    // 以真正锁住身份来源: 群成员身份必须取 member_openid, 群身份必须取 group_openid,
    // 而非协议里同名的 id / group_id. (真实 payload 两者同值, 此处刻意拆开来防回归.)
    val extRaw = "msg_idx=REFIDX_ndt+9Ej+8DrsoQfQM/xwVMtG81ovPjw88HwjHppK6Gc="
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "ROBOT1.0_msgid!",
        "content": "123",
        "timestamp": "2026-06-22T14:53:31+08:00",
        "author": {
          "id": "raw-author-id",
          "username": "第一个mt",
          "bot": false,
          "member_openid": "member-openid",
          "member_role": "owner",
          "union_openid": ""
        },
        "group_id": "raw-group-id",
        "group_openid": "group-openid",
        "message_scene": {
          "source": "default",
          "ext": [ "$extRaw" ]
        },
        "message_type": 0
      }
      """.trimIndent(),
    )

    // 身份不变: author.id 必须取 member_openid (非 author 的 id 字段), groupId 必须取 group_openid.
    assertEquals("member-openid", raw.author.id)
    assertEquals("group-openid", raw.groupId)

    // author 扩展资料字段.
    assertEquals("第一个mt", raw.author.username)
    assertEquals(false, raw.author.bot)
    assertEquals("owner", raw.author.memberRole)
    assertEquals("", raw.author.unionOpenid)

    // 冗余 group_id 被忠实记录, 但不替代 groupId, 故与 groupId 取值不同.
    assertEquals("raw-group-id", raw.rawGroupId)

    // message_scene 的 ext 原样保留不透明扩展值.
    val scene = assertNotNull(raw.messageScene, "message_scene 应被解析")
    assertEquals("default", scene.source)
    assertEquals(listOf(extRaw), scene.ext)

    assertEquals(0, raw.messageType)
  }

  @Test
  fun `旧 payload 缺扩展字段时新字段为缺省值不抛`() {
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "msg-old",
        "author": { "member_openid": "u-1" },
        "content": "hi",
        "timestamp": "2026-06-19T18:42:16+08:00",
        "group_openid": "g-1"
      }
      """.trimIndent(),
    )
    assertNull(raw.author.username)
    assertNull(raw.author.bot)
    assertNull(raw.author.memberRole)
    assertNull(raw.author.unionOpenid)
    assertNull(raw.rawGroupId)
    assertNull(raw.messageScene)
    assertNull(raw.messageType)
  }

  @Test
  fun `message_scene 缺 ext 时为空列表`() {
    val raw = json.decodeFromString(
      TencentGroupMessageRaw.serializer(),
      """
      {
        "id": "msg-2",
        "author": { "member_openid": "u-2" },
        "content": "hi",
        "timestamp": "2026-06-19T18:42:16+08:00",
        "group_openid": "g-2",
        "message_scene": { "source": "default" }
      }
      """.trimIndent(),
    )
    val scene = assertNotNull(raw.messageScene)
    assertEquals("default", scene.source)
    assertTrue(scene.ext.isEmpty(), "缺 ext 时应为空列表")
  }
}
