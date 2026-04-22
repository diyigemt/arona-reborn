package com.diyigemt.security

import com.diyigemt.arona.webui.endpoints.parseBearer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearerParseTest {
  @Test
  fun `null 与空白 header 视为无效`() {
    assertNull(parseBearer(null))
    assertNull(parseBearer(""))
    assertNull(parseBearer("   "))
  }

  @Test
  fun `Bearer scheme 大小写不敏感, 其他 scheme 拒绝`() {
    assertEquals("abc", parseBearer("Bearer abc"))
    assertEquals("abc", parseBearer("bearer abc"))
    assertEquals("abc", parseBearer("BEARER abc"))
    assertNull(parseBearer("Token abc"))
    assertNull(parseBearer("Basic abc"))
  }

  @Test
  fun `Bearer 后缺 token 视为无效`() {
    assertNull(parseBearer("Bearer"))
    assertNull(parseBearer("Bearer "))
    assertNull(parseBearer("Bearer    "))
  }

  @Test
  fun `token 内含空白字符视为无效`() {
    assertNull(parseBearer("Bearer abc def"))
    assertNull(parseBearer("Bearer abc\tdef"))
  }

  @Test
  fun `合法 Bearer 应返回 token 主体`() {
    assertEquals("abc", parseBearer("Bearer abc"))
    assertEquals("abc", parseBearer("  Bearer abc  "))
    assertEquals("abc", parseBearer("Bearer    abc"))
  }
}
