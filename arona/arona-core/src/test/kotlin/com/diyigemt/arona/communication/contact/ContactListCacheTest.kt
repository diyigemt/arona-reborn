package com.diyigemt.arona.communication.contact

import com.diyigemt.arona.communication.TencentBot
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.communication.message.MessageChain
import com.diyigemt.arona.communication.message.MessageReceipt
import com.diyigemt.arona.communication.message.TencentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 回归保护:
// - 旧实现 getOrCreate 不缓存, 每次返回新实例 → "同 id 返回同实例" 会失败.
// - 旧实现没有 Empty 升级路径 → "富对象替换 Empty" 会失败 (升级后应拿到新实例).
// - 旧实现 remove 只从队列移除, 不 cancel scope → "remove 会取消 scope" 会失败.
class ContactListCacheTest {

  private open class TestContact(
    override val id: String,
    override val coroutineContext: CoroutineContext = Dispatchers.Default + SupervisorJob(),
  ) : Contact {
    override val bot: TencentBot get() = error("bot unused in test")
    override val unionOpenid: String? = null
    override suspend fun sendMessage(message: Message, messageSequence: Int): MessageReceipt<Contact>? = null
    override suspend fun sendMessage(message: MessageChain, messageSequence: Int): MessageReceipt<Contact>? = null
    override suspend fun uploadImage(url: String): TencentImage = error("unused in test")
    override suspend fun uploadImage(data: ByteArray): TencentImage = error("unused in test")
  }

  private class EmptyTestContact(id: String) : TestContact(id), EmptyContact {
    override val id: String = id
  }

  private class RichTestContact(id: String) : TestContact(id)

  private class TestContactList(
    override val generator: (id: String) -> TestContact,
  ) : ContactList<TestContact>()

  @Test
  fun `同一 id 多次 getOrCreate 返回同一实例`() {
    val list = TestContactList(::EmptyTestContact)

    val a = list.getOrCreate("same")
    val b = list.getOrCreate("same")

    assertSame(a, b)
    assertEquals(1, list.size)
  }

  @Test
  fun `并发 getOrCreate 同一 id 只触发一次 factory`() {
    runBlocking {
      val invoked = AtomicInteger(0)
      val list = TestContactList(::EmptyTestContact)

      val results = (1..500).map {
        async {
          list.getOrCreate("same") {
            invoked.incrementAndGet()
            RichTestContact(it)
          }
        }
      }.awaitAll()

      assertEquals(1, invoked.get(), "factory should only run once under contention")
      assertEquals(1, results.distinctBy { System.identityHashCode(it) }.size)
    }
  }

  @Test
  fun `remove 后会取消 contact scope`() {
    val list = TestContactList(::EmptyTestContact)
    val c = list.getOrCreate("same")

    assertTrue(list.remove("same"))
    assertTrue(c.coroutineContext[Job]?.isCancelled == true)
    assertFalse("same" in list)
  }

  @Test
  fun `remove 后再次 getOrCreate 会创建新实例`() {
    val list = TestContactList(::EmptyTestContact)
    val old = list.getOrCreate("same")

    assertTrue(list.remove("same"))
    val new = list.getOrCreate("same")

    assertNotSame(old, new)
  }

  @Test
  fun `富对象 factory 会替换已缓存的 Empty 占位并取消旧 scope`() {
    val list = TestContactList(::EmptyTestContact)
    val placeholder = list.getOrCreate("same")
    assertTrue(placeholder is EmptyContact)

    val rich = list.getOrCreate("same") { RichTestContact(it) }

    assertTrue(rich is RichTestContact)
    assertNotSame(placeholder, rich)
    assertSame(rich, list["same"])
    assertTrue(placeholder.coroutineContext[Job]?.isCancelled == true)
    assertFalse(rich.coroutineContext[Job]?.isCancelled == true)
  }

  @Test
  fun `已有富对象时 factory 不会被重复调用也不会替换`() {
    val list = TestContactList(::EmptyTestContact)
    val invoked = AtomicInteger(0)
    val first = list.getOrCreate("same") {
      invoked.incrementAndGet()
      RichTestContact(it)
    }
    val second = list.getOrCreate("same") {
      invoked.incrementAndGet()
      RichTestContact(it)
    }

    assertSame(first, second)
    assertEquals(1, invoked.get(), "factory should not be invoked when a rich contact is cached")
    assertFalse(first.coroutineContext[Job]?.isCancelled == true)
  }
}
