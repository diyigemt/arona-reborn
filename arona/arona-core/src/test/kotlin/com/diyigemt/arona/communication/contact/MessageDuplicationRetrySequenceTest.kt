package com.diyigemt.arona.communication.contact

import kotlin.test.Test
import kotlin.test.assertTrue

// 回归保护:
// 旧实现重放用 `current + (100..1000).random()` 大跨度偏移, 腾讯 msg_seq 空间窄, 重放序号频繁落在合法范围之外,
// 又或者撞上同 sender 的下一条合法序列. 现在收紧到 `current + 1..4` 的止血范围.
// 反证: 改回 (100..1000).random() 后下面的边界断言会成片失败.
class MessageDuplicationRetrySequenceTest {

  @Test
  fun `重放序号始终落在 current 加 1 到 4 区间内`() {
    val current = 100
    repeat(2000) {
      val retry = computeMessageDuplicationRetrySequence(current)
      assertTrue(
        retry in (current + 1)..(current + 4),
        "retry 必须属于 [${current + 1}, ${current + 4}], 实际=$retry",
      )
    }
  }

  @Test
  fun `重放序号永不等于 current 自身`() {
    // 偏移最小为 1, 不能回吐 current 本身 (否则等于"原地重发", 腾讯会继续 Duplication).
    val current = 7
    repeat(2000) {
      val retry = computeMessageDuplicationRetrySequence(current)
      assertTrue(retry != current, "retry 必须 != current=$current, 实际=$retry")
    }
  }

  @Test
  fun `低值 current 下仍保持正偏移`() {
    // current=1 时 retry 至少为 2, 不能出现 0 或负数.
    repeat(500) {
      val retry = computeMessageDuplicationRetrySequence(1)
      assertTrue(retry in 2..5, "低值 current 下 retry 仍应正偏移 1..4, 实际=$retry")
    }
  }
}
