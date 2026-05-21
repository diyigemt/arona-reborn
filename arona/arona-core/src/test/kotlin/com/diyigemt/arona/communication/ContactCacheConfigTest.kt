package com.diyigemt.arona.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 锁住 C 项的"配置默认值 + 默认装配"语义:
// - TencentBotContactCacheConfig 的默认阈值被 plan 写过, 任何静默调小都会让 6w DAU 量级缓存策略漂移.
// - 所有顶层 + 内层字段都必须被默认初始化, 不能因为 data class 字段重排被遗漏.
class ContactCacheConfigTest {

  @Test
  fun `TencentBotContactCacheConfig 默认值符合 6w DAU 量级`() {
    val cfg = TencentBotContactCacheConfig()
    // 顶层 (bot.guilds / bot.groups / bot.friends)
    assertEquals(1_000L, cfg.guilds.maximumSize)
    assertEquals(7 * 24 * 60 * 60L, cfg.guilds.expireAfterAccessSeconds)
    assertEquals(10_000L, cfg.groups.maximumSize)
    assertEquals(24 * 60 * 60L, cfg.groups.expireAfterAccessSeconds)
    assertEquals(100_000L, cfg.friends.maximumSize)
    assertEquals(24 * 60 * 60L, cfg.friends.expireAfterAccessSeconds)
    // 内层 (Guild/Group/Channel 内嵌 members/channels): GROUP_MESSAGE_CREATE 启用后 inner 不再无界.
    assertEquals(5_000L, cfg.guildMembers.maximumSize)
    assertEquals(2 * 60 * 60L, cfg.guildMembers.expireAfterAccessSeconds)
    assertEquals(500L, cfg.guildChannels.maximumSize)
    assertEquals(7 * 24 * 60 * 60L, cfg.guildChannels.expireAfterAccessSeconds)
    assertEquals(2_000L, cfg.channelMembers.maximumSize)
    assertEquals(2 * 60 * 60L, cfg.channelMembers.expireAfterAccessSeconds)
    assertEquals(3_000L, cfg.groupMembers.maximumSize)
    assertEquals(2 * 60 * 60L, cfg.groupMembers.expireAfterAccessSeconds)
  }

  @Test
  fun `TencentBotConfig 默认 contactCache 不为空, 七类 ContactList 都有阈值`() {
    val cfg = TencentBotConfig(
      id = "id",
      appId = "app",
      token = "t",
      secret = "s",
    )
    val tunings = with(cfg.contactCache) {
      listOf(guilds, groups, friends, guildMembers, guildChannels, channelMembers, groupMembers)
    }
    tunings.forEach {
      assertTrue(it.maximumSize > 0)
      assertTrue(it.expireAfterAccessSeconds > 0)
    }
  }
}
