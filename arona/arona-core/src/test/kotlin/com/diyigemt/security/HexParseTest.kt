package com.diyigemt.security

import com.diyigemt.arona.webui.endpoints.parseHexOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class HexParseTest {
  @Test
  fun `合法 hex 解析为字节数组`() {
    assertContentEquals(byteArrayOf(0x0A, 0x1B), parseHexOrNull("0A1b"))
  }

  @Test
  fun `奇数长度 hex 返回 null`() {
    assertNull(parseHexOrNull("abc"))
    assertNull(parseHexOrNull("0"))
  }

  @Test
  fun `非 hex 字符返回 null`() {
    assertNull(parseHexOrNull("zz"))
    assertNull(parseHexOrNull("12xz"))
    assertNull(parseHexOrNull("--"))
    assertNull(parseHexOrNull("0g"))
  }

  @Test
  fun `空串返回空数组`() {
    assertContentEquals(byteArrayOf(), parseHexOrNull(""))
  }
}
