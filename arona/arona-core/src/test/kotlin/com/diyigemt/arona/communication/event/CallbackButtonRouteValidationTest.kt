package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonChatType
import com.diyigemt.arona.communication.TencentWebsocketCallbackButtonType
import com.diyigemt.arona.communication.message.TencentWebsocketCallbackButtonDataResolvedResp
import com.diyigemt.arona.communication.message.TencentWebsocketCallbackButtonDataResp
import com.diyigemt.arona.communication.message.TencentWebsocketCallbackButtonResp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 回归保护:
// - 旧实现 handler 在所有分支用 `?: ""` 兜底, 缺字段时构造 id="" 的 Empty Contact;
//   Sprint 1.2 起 ContactList 是 ConcurrentHashMap 真缓存, id="" 占位会永久驻留;
// - 本 validator 抓住这三条路径的必需字段, handler 走 warn+return 不再污染缓存.
class CallbackButtonRouteValidationTest {

  private fun payload(
    chatType: TencentWebsocketCallbackButtonChatType,
    guildId: String? = "guild-1",
    channelId: String? = "channel-1",
    userOpenId: String? = "friend-1",
    groupOpenid: String? = "group-1",
    groupMemberOpenid: String? = "group-member-1",
    resolvedUserId: String? = "user-1",
  ) = TencentWebsocketCallbackButtonResp(
    id = "interaction-1",
    type = TencentWebsocketCallbackButtonType.MessageButton,
    chatType = chatType,
    data = TencentWebsocketCallbackButtonDataResp(
      resolved = TencentWebsocketCallbackButtonDataResolvedResp(
        buttonId = "btn-1",
        userId = resolvedUserId,
      ),
      type = TencentWebsocketCallbackButtonType.MessageButton,
    ),
    timestamp = "0",
    guildId = guildId,
    channelId = channelId,
    userOpenId = userOpenId,
    groupOpenid = groupOpenid,
    groupMemberOpenid = groupMemberOpenid,
  )

  @Test
  fun `Guild 路径字段齐全时返回 null`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Guild)
    assertNull(p.missingCallbackRouteField())
  }

  @Test
  fun `Guild 路径缺 guildId 返回字段名`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Guild, guildId = null)
    assertEquals("guildId", p.missingCallbackRouteField())
  }

  @Test
  fun `Guild 路径 guildId 仅空白也要短路`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Guild, guildId = "   ")
    assertEquals("guildId", p.missingCallbackRouteField())
  }

  @Test
  fun `Guild 路径缺 channelId 返回字段名`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Guild, channelId = null)
    assertEquals("channelId", p.missingCallbackRouteField())
  }

  @Test
  fun `Guild 路径缺 resolved userId 返回字段名`() {
    // 事件模型 TencentCallbackButtonEvent.user 非空, Guild 分支 userId 缺失若兜底就是 Empty 假 member 永久驻留.
    val p = payload(TencentWebsocketCallbackButtonChatType.Guild, resolvedUserId = null)
    assertEquals("resolved.userId", p.missingCallbackRouteField())
  }

  @Test
  fun `Group 路径字段齐全时返回 null`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Group)
    assertNull(p.missingCallbackRouteField())
  }

  @Test
  fun `Group 路径缺 groupOpenid 返回字段名`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Group, groupOpenid = null)
    assertEquals("groupOpenid", p.missingCallbackRouteField())
  }

  @Test
  fun `Group 路径 groupMemberOpenid 空串也要短路`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Group, groupMemberOpenid = "")
    assertEquals("groupMemberOpenid", p.missingCallbackRouteField())
  }

  @Test
  fun `Friend 路径字段齐全时返回 null`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Friend)
    assertNull(p.missingCallbackRouteField())
  }

  @Test
  fun `Friend 路径缺 userOpenId 返回字段名`() {
    val p = payload(TencentWebsocketCallbackButtonChatType.Friend, userOpenId = "")
    assertEquals("userOpenId", p.missingCallbackRouteField())
  }

  @Test
  fun `多字段同时缺失时返回第一个被依赖的`() {
    // 同时缺多个字段时 validator 必须优先报最早的那个, 避免用户 fix 其中一个后才发现还有别的.
    val p = payload(
      TencentWebsocketCallbackButtonChatType.Guild,
      guildId = null,
      channelId = null,
      resolvedUserId = null,
    )
    assertEquals("guildId", p.missingCallbackRouteField())
  }

  @Test
  fun `Guild 路径其他必需字段齐全仅 Group 字段缺失不影响`() {
    // 交叉验证: Guild 分支不应关心 groupOpenid / groupMemberOpenid / userOpenId.
    val p = payload(
      TencentWebsocketCallbackButtonChatType.Guild,
      groupOpenid = null,
      groupMemberOpenid = null,
      userOpenId = null,
    )
    assertNull(p.missingCallbackRouteField())
  }
}
