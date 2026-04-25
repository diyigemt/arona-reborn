package com.diyigemt.arona.communication.message

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

// Sprint 3.5(a) 锁住 MessageChainBuilder.build() 返回不可变快照:
//  - 旧实现 build() 把 builder 自身 (其 container 委托) 当成 chain 的 delegate, 调用方 build() 后继续
//    builder.append(...) 会污染已发出的 MessageChain. 这是别名 bug, 不是 immutable contract bug.
//  - 新实现 build() 之后 builder 与 chain 解耦. 仅修 build(), 不动 MessageChainImpl 自身的 mutability.
class MessageChainBuilderSnapshotTest {

  @Test
  fun `build 后继续 append 不应污染已发出的 chain`() {
    val builder = MessageChainBuilder()
      .append(PlainText("hello"))
    val chain = builder.build()
    assertEquals(1, chain.size, "build 时只有 1 元素")

    // 旧实现这一步会把 PlainText("追加") 加到 chain 的 delegate (即 builder 自己) 上.
    builder.append(PlainText("追加"))

    assertEquals(1, chain.size, "build 之后再 append 不能影响已发出的 chain")
    assertTrue(chain.first() is PlainText)
    assertEquals("hello", (chain.first() as PlainText).toString())
  }

  @Test
  fun `连续两次 build 互不影响`() {
    val builder = MessageChainBuilder()
      .append(PlainText("a"))
    val first = builder.build()
    builder.append(PlainText("b"))
    val second = builder.build()

    assertEquals(1, first.size, "first chain 锁定 build 时刻状态")
    assertEquals(2, second.size, "second chain 反映后续状态")
    assertNotSame(first, second, "两条 chain 不能是同一对象")
  }

  @Test
  fun `build 后不再依赖 builder 的 container 引用`() {
    // 反证视角: 若 build 仍然把 this 传进去, builder.clear() 会清空已发出 chain.
    val builder = MessageChainBuilder()
      .append(PlainText("x"))
      .append(PlainText("y"))
    val chain = builder.build()
    assertEquals(2, chain.size)

    builder.clear()

    assertEquals(2, chain.size, "builder.clear() 不能清空已发出 chain")
  }
}
