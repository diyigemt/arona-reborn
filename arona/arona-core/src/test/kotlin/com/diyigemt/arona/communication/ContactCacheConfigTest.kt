package com.diyigemt.arona.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 锁住 C 项的"配置默认值 + 默认装配"语义:
// - TencentBotContactCacheConfig 的默认阈值被 plan 写过, 任何静默调小都会让 6w DAU 量级缓存策略漂移.
// - guilds/groups/friends 三个字段都必须被默认初始化, 不能因为 data class 字段重排被遗漏.
class ContactCacheConfigTest {

  @Test
  fun `TencentBotContactCacheConfig 默认值符合 6w DAU 量级`() {
    val cfg = TencentBotContactCacheConfig()
    assertEquals(1_000L, cfg.guilds.maximumSize)
    assertEquals(7 * 24 * 60 * 60L, cfg.guilds.expireAfterAccessSeconds)
    assertEquals(10_000L, cfg.groups.maximumSize)
    assertEquals(24 * 60 * 60L, cfg.groups.expireAfterAccessSeconds)
    assertEquals(100_000L, cfg.friends.maximumSize)
    assertEquals(24 * 60 * 60L, cfg.friends.expireAfterAccessSeconds)
  }

  @Test
  fun `TencentBotConfig 默认 contactCache 不为空, 三类 ContactList 都有阈值`() {
    val cfg = TencentBotConfig(
      id = "id",
      appId = "app",
      token = "t",
      secret = "s",
    )
    assertTrue(cfg.contactCache.guilds.maximumSize > 0)
    assertTrue(cfg.contactCache.groups.maximumSize > 0)
    assertTrue(cfg.contactCache.friends.maximumSize > 0)
    assertTrue(cfg.contactCache.guilds.expireAfterAccessSeconds > 0)
    assertTrue(cfg.contactCache.groups.expireAfterAccessSeconds > 0)
    assertTrue(cfg.contactCache.friends.expireAfterAccessSeconds > 0)
  }
}
