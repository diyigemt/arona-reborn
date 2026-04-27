package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.contact.Guild.Companion.batchResolveMemberPrivateChannels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 直接覆盖 D 项 batch helper 的纯逻辑短路:
// - 空输入 / 全空字符串 id 不应触发 sqlDbQuery (避免冷启动期空 guild 也跑一次空 IN 查询).
// - 这条路径下 Guild 没有真实 DB, 任何 sqlDbQuery 调用都会因 DatabaseProvider 未初始化而抛.
//   所以"测试不抛异常 + 返回 emptyMap" = 短路成立.
class GuildBatchResolveMemberChannelsTest {

  @Test
  fun `空 memberIds 短路返回 emptyMap, 不触达 sqlDbQuery`() {
    val bot = StubBot()
    try {
      val guild = EmptyGuildImpl(bot)
      val result = guild.batchResolveMemberPrivateChannels(emptyList())
      assertEquals(emptyMap(), result)
    } finally {
      bot.close()
    }
  }

  @Test
  fun `全空字符串 id 短路返回 emptyMap, 不触达 sqlDbQuery`() {
    val bot = StubBot()
    try {
      val guild = EmptyGuildImpl(bot)
      val result = guild.batchResolveMemberPrivateChannels(listOf("", "", ""))
      assertTrue(result.isEmpty())
    } finally {
      bot.close()
    }
  }
}
