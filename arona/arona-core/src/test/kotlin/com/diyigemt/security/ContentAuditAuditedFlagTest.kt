package com.diyigemt.security

import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.broadcast
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.diyigemt.arona.webui.event.isBlock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `audited` 是 fail-closed 调用方区分 "审核通过" 与 "没人审 / 审一半挂了" 的唯一依据:
 * 默认 false, 零监听器 broadcast 不会动它, 监听器抛异常也不会走到置位.
 */
class ContentAuditAuditedFlagTest {
  @Test
  fun `默认 audited 为 false 且 pass 为 true`() {
    val ev = ContentAuditEvent("text")
    assertFalse(ev.audited)
    assertFalse(ev.isBlock)
  }

  @Test
  fun `零监听器 broadcast 后 audited 仍为 false`() = runBlocking {
    // 没装 content-audit 插件时 callListeners 直接返回, 不会有人置位 —— fail-closed 调用方据此拒绝.
    val ev = ContentAuditEvent("zero-listener-audited-flag-test").broadcast()
    assertFalse(ev.audited)
    assertFalse(ev.isBlock)
  }

  @Test
  fun `监听器抛异常时 audited 保持 false`() = runBlocking {
    val listener = GlobalEventChannel.subscribeAlways<ContentAuditEvent> {
      if (it.value == "boom-audited-flag-test") error("audit backend down")
    }
    try {
      val ev = ContentAuditEvent("boom-audited-flag-test").broadcast()
      assertFalse(ev.audited, "异常被 SafeListener 吞掉后不应视为已审核")
      assertFalse(ev.isBlock, "pass 默认 true 正是 audited 存在的理由")
    } finally {
      listener.complete()
    }
  }

  @Test
  fun `监听器同步置位后 audited 为 true`() = runBlocking {
    val listener = GlobalEventChannel.subscribeAlways<ContentAuditEvent> {
      if (it.value == "ok-audited-flag-test") it.audited = true
    }
    try {
      assertTrue(ContentAuditEvent("ok-audited-flag-test").broadcast().audited)
    } finally {
      listener.complete()
    }
  }
}
