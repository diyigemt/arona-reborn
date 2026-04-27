package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.contact.GuildChannelMemberImpl
import com.diyigemt.arona.communication.contact.GuildMemberImpl
import com.diyigemt.arona.communication.contact.StubBot
import com.diyigemt.arona.communication.message.TencentGuildMemberRaw
import com.diyigemt.arona.communication.message.TencentGuildUserRaw
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// 反证 A 项修复 (AbstractContact 借用模式) 是否真的消除了"每条频道消息泄漏一个 SupervisorJob"的核心问题:
// - 旧实现 GuildChannelMemberImpl / GuildMemberImpl 构造时走 childScopeContext() 派生独立 SupervisorJob,
//   局部变量出作用域后 Job 仍被父 children 链表强引用, 不会被 GC. 1000 条消息 = 1000 个常驻 Job.
// - 新实现把这两个类标记 ownsCoroutineScope=false, 直接复用父 (channel/guild) coroutineContext.
//
// 用例直接重复构造这两个类的实例 1000 次, 断言父 channel/guild 的 Job.children.count() 不增长. 旧实现下
// 此断言必失败, 新实现下增长应为 0.
class MessageDispatchScopeLeakTest {

  @Test
  fun `重复构造 GuildChannelMemberImpl 不会让父 channel Job children 增长`() {
    val bot = StubBot()
    try {
      val guild = bot.guilds.getOrCreate("g-leak-channel")
      val channel = guild.channels.getOrCreate("c-leak")
      val emptyMember = guild.members.getOrCreate("u-leak")
      val channelJob = assertNotNull(channel.coroutineContext[Job])

      val baseline = channelJob.children.count()
      repeat(1000) {
        GuildChannelMemberImpl(channel, emptyMember)
      }
      val growth = channelJob.children.count() - baseline

      assertEquals(
        0, growth,
        "borrow 模式 leaf 不应在父 channel Job 下登记 child; 旧实现下会涨到 1000",
      )
    } finally {
      bot.close()
    }
  }

  @Test
  fun `重复构造 GuildMemberImpl (DM 临时 sender) 不会让父 guild Job children 增长`() {
    val bot = StubBot()
    try {
      val guild = bot.guilds.getOrCreate("g-leak-guild")
      val channel = guild.channels.getOrCreate("c-leak-guild")
      val guildJob = assertNotNull(guild.coroutineContext[Job])
      val memberRaw = TencentGuildMemberRaw(
        joinedAt = "0",
        user = TencentGuildUserRaw(id = "u", avatar = "", username = "x"),
      )

      val baseline = guildJob.children.count()
      repeat(1000) {
        GuildMemberImpl(guild, channel, memberRaw)
      }
      val growth = guildJob.children.count() - baseline

      assertEquals(
        0, growth,
        "borrow 模式 leaf 不应在父 guild Job 下登记 child; 旧实现下会涨到 1000",
      )
    } finally {
      bot.close()
    }
  }
}
