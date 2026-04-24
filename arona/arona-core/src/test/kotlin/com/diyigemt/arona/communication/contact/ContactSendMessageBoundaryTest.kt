package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentGuildRaw
import com.diyigemt.arona.communication.message.toMessageChain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 回归保护:
// 旧实现这些方法是 TODO() / NotImplementedError, 触发时信息量极弱, 让调用方误以为是"还没写完"而不是"架构层面就不支持".
// 现在用 UnsupportedOperationException (类型层面不支持) 或 IllegalStateException (实例占位状态不合法) 显式区分.
class ContactSendMessageBoundaryTest {

  @Test
  fun `GuildImpl sendMessage 抛 UnsupportedOperationException 要求调用方发到 Channel`() {
    val bot = StubBot()
    try {
      // 直接构造 GuildImpl 避免被 StubBot 的 guilds 工厂返回 EmptyGuildImpl (其走 IllegalStateException 分支).
      val guild = GuildImpl(
        bot = bot,
        parentCoroutineContext = bot.coroutineContext,
        internalGuild = TencentGuildRaw(
          id = "guild-real",
          name = "test",
          icon = "",
          ownerId = "",
          owner = false,
          memberCount = 0,
          maxMembers = 0,
          description = "",
          joinedAt = "",
        ),
      )
      runBlocking {
        val ex = assertFailsWith<UnsupportedOperationException> {
          guild.sendMessage(PlainText("x").toMessageChain(), 1)
        }
        assertTrue(
          ex.message?.contains("Channel") == true,
          "异常消息应提示 Channel 作为替代路径, 当前=${ex.message}",
        )
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `EmptyGuildImpl sendMessage 抛 IllegalStateException 说明是占位`() {
    val bot = StubBot()
    try {
      runBlocking {
        val emptyGuild = EmptyGuildImpl(bot, "placeholder-guild")
        val ex = assertFailsWith<IllegalStateException> {
          emptyGuild.sendMessage(PlainText("x").toMessageChain(), 1)
        }
        assertSame(true, ex.message?.contains("placeholder"), "异常消息应提示 placeholder, 当前=${ex.message}")
      }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `EmptyGroupMemberImpl 两个方法都抛 IllegalStateException`() {
    val bot = StubBot()
    try {
      val group = bot.groups.getOrCreate("g1")
      val member = EmptyGroupMemberImpl(group, "placeholder-member")

      runBlocking {
        assertFailsWith<IllegalStateException> {
          member.sendMessage(PlainText("x").toMessageChain(), 1)
        }
      }
      assertFailsWith<IllegalStateException> { member.asSingleUser() }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `EmptyMockGroupMemberImpl 两个方法都抛 IllegalStateException`() {
    val bot = StubBot()
    try {
      val group = bot.groups.getOrCreate("g-mock")
      val member = EmptyMockGroupMemberImpl(group, "placeholder-mock-member")

      runBlocking {
        assertFailsWith<IllegalStateException> {
          member.sendMessage(PlainText("x").toMessageChain(), 1)
        }
      }
      assertFailsWith<IllegalStateException> { member.asSingleUser() }
    } finally {
      bot.close()
    }
  }

  @Test
  fun `GroupMemberImpl asSingleUser 经 friends 缓存返回同一 FriendUser`() {
    // Sprint 1.2 ContactList 真缓存 + 2.3 asSingleUser 改成 bot.friends.getOrCreate(id) 组合保证:
    // 同 id 重复 asSingleUser 必然返回同一引用, 不会反复构造新 FriendUser.
    val bot = StubBot()
    try {
      val group = bot.groups.getOrCreate("g2")
      val member = GroupMemberImpl(
        parentCoroutineContext = group.coroutineContext,
        id = "member-1",
        group = group,
      )

      val a = member.asSingleUser()
      val b = member.asSingleUser()
      assertSame(a, b, "同一 member 的 asSingleUser() 必须稳定返回同一 FriendUser")

      val fromFriendsDirectly = bot.friends.getOrCreate("member-1")
      assertSame(a, fromFriendsDirectly, "asSingleUser 应走 friends 缓存, 不新造实例")
    } finally {
      bot.close()
    }
  }
}
