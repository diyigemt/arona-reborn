package com.diyigemt.security

import com.diyigemt.arona.webui.event.auditOrAllow
import com.diyigemt.arona.webui.event.isBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 覆盖 P2 引入的 auditOrAllow 超时/异常降级行为,
 * 保证内容审核插件不会拖死调用链路.
 */
class AuditTimeoutTest {
  @Test
  fun `正常审核返回 event`() = runBlocking {
    val audit = auditOrAllow("正常内容", timeoutMillis = 200) { it }
    assertEquals("Normal", audit?.message)
    assertTrue(audit?.isBlock == false)
  }

  @Test
  fun `审核置 pass 为 false 时 isBlock 为 true`() = runBlocking {
    val audit = auditOrAllow("敏感内容", timeoutMillis = 200) {
      it.pass = false
      it.message = "blocked"
      it
    }
    assertTrue(audit?.isBlock == true)
    assertEquals("blocked", audit?.message)
  }

  @Test
  fun `超时应降级为 null`() = runBlocking {
    val audit = auditOrAllow("超时内容", timeoutMillis = 20) {
      delay(200)
      it
    }
    assertNull(audit)
  }

  @Test
  fun `异常应降级为 null`() = runBlocking {
    val audit = auditOrAllow("异常内容", timeoutMillis = 200) {
      error("audit failed")
    }
    assertNull(audit)
  }
}
