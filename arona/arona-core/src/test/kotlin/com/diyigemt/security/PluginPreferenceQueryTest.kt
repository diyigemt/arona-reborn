package com.diyigemt.security

import com.diyigemt.arona.webui.endpoints.plugin.PreferenceQuery
import com.diyigemt.arona.webui.endpoints.plugin.parsePreferenceQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 固化 P3-2: GET /plugin/preference 协议从路径参数纠正为 query 参数.
 * 抽出 parsePreferenceQuery 纯函数后只测它即可, 不需要拉起 Ktor.
 */
class PluginPreferenceQueryTest {
  @Test
  fun `缺少 id 时返回 null`() {
    assertNull(parsePreferenceQuery(null, null))
    assertNull(parsePreferenceQuery(null, "theme"))
    assertNull(parsePreferenceQuery("", "theme"))
    assertNull(parsePreferenceQuery("   ", "theme"))
  }

  @Test
  fun `只有 id 时表示读取整组配置`() {
    assertEquals(
      PreferenceQuery("plugin.demo", null),
      parsePreferenceQuery("plugin.demo", null),
    )
    // 空 key 视为未传, 与未传等价.
    assertEquals(
      PreferenceQuery("plugin.demo", null),
      parsePreferenceQuery("plugin.demo", ""),
    )
    assertEquals(
      PreferenceQuery("plugin.demo", null),
      parsePreferenceQuery("plugin.demo", "   "),
    )
  }

  @Test
  fun `id 与 key 同时存在时表示读取单项`() {
    assertEquals(
      PreferenceQuery("plugin.demo", "theme"),
      parsePreferenceQuery("plugin.demo", "theme"),
    )
  }
}
