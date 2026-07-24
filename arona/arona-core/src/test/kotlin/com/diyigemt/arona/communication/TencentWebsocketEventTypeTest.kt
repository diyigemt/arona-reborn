package com.diyigemt.arona.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

// 回归保护: 历史上枚举尾部残留过死条目 A("GUILD_CREATE"), 与正牌 GUILD_CREATE 重键.
// companion 的 associateBy 对重复 key 静默保留靠后项, 于是 fromValue("GUILD_CREATE") 解析成
// 无 handler 的 A, 线上 GUILD_CREATE 事件被 dispatch 静默丢弃. 这里把 wire 名唯一性和
// "字符串 -> 枚举" 的往返钉死, 防止再次引入重键.
class TencentWebsocketEventTypeTest {

  @Test
  fun `wire 字符串在全枚举内唯一`() {
    val types = TencentWebsocketEventType.entries.map { it.type }
    assertEquals(
      types.size,
      types.toSet().size,
      "存在重复 wire 字符串: ${types.groupBy { it }.filterValues { it.size > 1 }.keys}",
    )
  }

  @Test
  fun `每个枚举都能经 fromValue 往返解析回自身`() {
    TencentWebsocketEventType.entries.forEach { expected ->
      assertSame(expected, TencentWebsocketEventType.fromValue(expected.type))
    }
  }

  @Test
  fun `GUILD_CREATE 解析回正牌枚举`() {
    assertSame(
      TencentWebsocketEventType.GUILD_CREATE,
      TencentWebsocketEventType.fromValue("GUILD_CREATE"),
    )
  }

  @Test
  fun `未知 wire 字符串落到 NULL`() {
    assertSame(TencentWebsocketEventType.NULL, TencentWebsocketEventType.fromValue("NO_SUCH_EVENT"))
  }
}
