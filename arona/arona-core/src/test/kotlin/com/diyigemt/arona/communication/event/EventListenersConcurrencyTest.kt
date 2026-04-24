package com.diyigemt.arona.communication.event

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 回归保护: callListeners 过去在 for 循环里逐次 supervisorScope, 导致监听器串行等待;
// 这里直接驱动 EventListeners 验证并发 / 串行 (SerializedEvent) / STOPPED 三条路径都正确.
private data class PlainTestEvent(val tag: Int = 0) : Event
private data class OrderedTestEvent(val tag: Int = 0) : SerializedEvent

private fun <E : Event> listener(block: suspend (E) -> ListeningStatus): SafeListener<E> =
  SafeListener(parentJob = null, subscriberContext = EmptyCoroutineContext, listenerBlock = block)

class EventListenersConcurrencyTest {
  @Test
  fun `普通事件的多个 listener 会并发执行而不是串行等待`() {
    runBlocking {
      val listeners = EventListeners()
      val called = AtomicInteger(0)

      listOf(100L, 200L, 300L).forEach { delayMs ->
        listeners.addListener(PlainTestEvent::class, listener<PlainTestEvent> {
          delay(delayMs)
          called.incrementAndGet()
          ListeningStatus.LISTENING
        })
      }

      val elapsed = measureTimeMillis {
        listeners.callListeners(PlainTestEvent())
      }

      assertEquals(3, called.get())
      assertTrue(
        elapsed < 500,
        "concurrent listeners should finish near max(delay); elapsed=${elapsed}ms",
      )
    }
  }

  @Test
  fun `某个 listener 抛异常不会阻断其他 listener`() {
    runBlocking {
      val listeners = EventListeners()
      val survivorCalled = AtomicInteger(0)

      listeners.addListener(PlainTestEvent::class, listener<PlainTestEvent> {
        error("boom")
      })
      listeners.addListener(PlainTestEvent::class, listener<PlainTestEvent> {
        survivorCalled.incrementAndGet()
        ListeningStatus.LISTENING
      })

      listeners.callListeners(PlainTestEvent())

      assertEquals(1, survivorCalled.get())
    }
  }

  @Test
  fun `返回 STOPPED 的 listener 不会被下一次广播再次调用`() {
    runBlocking {
      val listeners = EventListeners()
      val calls = AtomicInteger(0)

      listeners.addListener(PlainTestEvent::class, listener<PlainTestEvent> {
        calls.incrementAndGet()
        ListeningStatus.STOPPED
      })

      listeners.callListeners(PlainTestEvent(1))
      listeners.callListeners(PlainTestEvent(2))

      assertEquals(1, calls.get())
    }
  }

  @Test
  fun `SerializedEvent 保持注册顺序串行执行`() {
    runBlocking {
      val listeners = EventListeners()
      val order = ConcurrentLinkedQueue<Int>()

      // 先注册的 listener 故意 delay 更久; 若走并发, 顺序会被打乱 (如 [3,2,1] / [2,1,3] 等);
      // 走串行时必然按注册顺序 [1,2,3].
      listeners.addListener(OrderedTestEvent::class, listener<OrderedTestEvent> {
        delay(80)
        order.add(1)
        ListeningStatus.LISTENING
      })
      listeners.addListener(OrderedTestEvent::class, listener<OrderedTestEvent> {
        delay(40)
        order.add(2)
        ListeningStatus.LISTENING
      })
      listeners.addListener(OrderedTestEvent::class, listener<OrderedTestEvent> {
        order.add(3)
        ListeningStatus.LISTENING
      })

      listeners.callListeners(OrderedTestEvent())

      assertEquals(listOf(1, 2, 3), order.toList())
    }
  }

  @Test
  fun `SerializedEvent 串行分支中某个 listener 抛异常不会阻断后续 listener`() {
    runBlocking {
      val listeners = EventListeners()
      val afterBoom = AtomicInteger(0)

      listeners.addListener(OrderedTestEvent::class, listener<OrderedTestEvent> {
        error("boom in serialized branch")
      })
      listeners.addListener(OrderedTestEvent::class, listener<OrderedTestEvent> {
        afterBoom.incrementAndGet()
        ListeningStatus.LISTENING
      })

      listeners.callListeners(OrderedTestEvent())

      assertEquals(1, afterBoom.get())
    }
  }

  @Test
  fun `大量 listener 并发执行不应抛 ConcurrentModificationException`() {
    runBlocking {
      val listeners = EventListeners()
      val called = AtomicInteger(0)

      repeat(50) {
        listeners.addListener(PlainTestEvent::class, listener<PlainTestEvent> {
          delay(5)
          called.incrementAndGet()
          ListeningStatus.LISTENING
        })
      }

      repeat(5) { round ->
        listeners.callListeners(PlainTestEvent(round))
      }

      assertEquals(50 * 5, called.get())
    }
  }
}
