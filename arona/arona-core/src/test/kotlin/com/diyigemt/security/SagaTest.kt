package com.diyigemt.security

import com.diyigemt.arona.utils.runSagaOrRollback
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 覆盖 P2 引入的 runSagaOrRollback 行为, 用以固化跨库写补偿模式.
 */
class SagaTest {
  @Test
  fun `action 成功时不触发 rollback`() = runBlocking {
    var rolledBack = false
    val v = runSagaOrRollback(
      rollback = { rolledBack = true },
    ) { 42 }
    assertEquals(42, v)
    assertTrue(!rolledBack)
  }

  @Test
  fun `action 抛异常时 rollback 被调用并重抛原异常`() = runBlocking {
    var rolledBack = false
    val ex = assertFailsWith<IllegalStateException> {
      runSagaOrRollback(
        rollback = { rolledBack = true },
      ) { error("boom") }
    }
    assertEquals("boom", ex.message)
    assertTrue(rolledBack)
  }

  @Test
  fun `rollback 抛异常被附加到原异常的 suppressed 列表`() = runBlocking {
    val ex = assertFailsWith<IllegalStateException> {
      runSagaOrRollback(
        rollback = { error("rollback failed") },
      ) { error("primary failure") }
    }
    assertEquals("primary failure", ex.message)
    assertEquals(1, ex.suppressed.size)
    assertEquals("rollback failed", ex.suppressed[0].message)
  }
}
