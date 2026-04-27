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

  private class TestContactList : ContactList<TestContact> {
    constructor(generator: (id: String) -> TestContact) : super(generator)
    constructor(
      maximumSize: Long,
      expireAfterAccessSeconds: Long,
      generator: (id: String) -> TestContact,
    ) : super(maximumSize, expireAfterAccessSeconds, generator)
  }

  /** 借用父 scope 的 leaf contact 测试替身: 不持有独立 Job, ownsCoroutineScope = false. */
  private class BorrowedTestContact(
    id: String,
    parentCoroutineContext: CoroutineContext,
  ) : TestContact(id, parentCoroutineContext) {
    override val ownsCoroutineScope: Boolean = false
  }

  /** 借用模式 + Empty 占位组合: 模拟 leaf member ContactList 默认 generator 的行为. */
  private class BorrowedEmptyTestContact(
    id: String,
    parentCoroutineContext: CoroutineContext,
  ) : TestContact(id, parentCoroutineContext), EmptyContact {
    override val id: String = id
    override val ownsCoroutineScope: Boolean = false
  }

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
  fun `borrow 模式 contact 与父共享 Job 而非派生新 Job`() {
    val parentJob = SupervisorJob()
    val parentContext: CoroutineContext = Dispatchers.Default + parentJob
    val borrowed = BorrowedTestContact("a", parentContext)

    assertSame(parentJob, borrowed.coroutineContext[Job], "borrow 模式必须直接复用父 Job")
    assertFalse(borrowed.ownsCoroutineScope)
  }

  @Test
  fun `remove 借用模式 entry 不会取消父 Job`() {
    val parentJob = SupervisorJob()
    val parentContext: CoroutineContext = Dispatchers.Default + parentJob
    val list = TestContactList { id -> BorrowedTestContact(id, parentContext) }

    list.getOrCreate("leaf")
    assertTrue(list.remove("leaf"))

    assertFalse(parentJob.isCancelled, "ContactList.remove 不能 cancel borrow 模式 entry 的父 Job")
    assertFalse(parentJob.isCompleted)
  }

  @Test
  fun `富对象升级借用模式 Empty 占位时不会 cancel 父 Job`() {
    val parentJob = SupervisorJob()
    val parentContext: CoroutineContext = Dispatchers.Default + parentJob
    val list = TestContactList { id -> BorrowedEmptyTestContact(id, parentContext) }

    val placeholder = list.getOrCreate("a")
    assertTrue(placeholder is EmptyContact)
    assertFalse(placeholder.ownsCoroutineScope)

    list.getOrCreate("a") { RichTestContact(it) }

    assertFalse(parentJob.isCancelled, "borrow 模式 Empty 占位升级不应连锁 cancel 父 scope")
  }

  @Test
  fun `有界 ContactList 写满后驱逐老条目并 cancel 其 Job`() {
    val list = TestContactList(
      maximumSize = 2L,
      expireAfterAccessSeconds = 3600L,
    ) { id -> RichTestContact(id) }

    val a = list.getOrCreate("a")
    val b = list.getOrCreate("b")
    val c = list.getOrCreate("c")
    // Caffeine size eviction 是异步的 (默认 ForkJoinPool); 主动触发 maintenance + 等到稳态.
    val cache = list as ContactList<TestContact>
    val deadline = System.currentTimeMillis() + 2000L
    while (cache.size > 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L)
    }

    assertEquals(2, cache.size, "maximumSize=2 触发后只应保留 2 条")
    // 被驱逐那条的 Job 必然 cancel; a/b/c 哪条先被淘汰由 Caffeine 选, 我们只断言"至少有一条 owns Job 被 cancel".
    val cancelledCount = listOf(a, b, c).count { it.coroutineContext[Job]?.isCancelled == true }
    assertTrue(cancelledCount >= 1, "被驱逐 owns-scope contact 的 Job 必须被 cancel")
  }

  @Test
  fun `有界 ContactList 驱逐借用模式 entry 时不会 cancel 父 Job`() {
    val parentJob = SupervisorJob()
    val parentContext: CoroutineContext = Dispatchers.Default + parentJob
    val list = TestContactList(
      maximumSize = 2L,
      expireAfterAccessSeconds = 3600L,
    ) { id -> BorrowedTestContact(id, parentContext) }

    list.getOrCreate("a")
    list.getOrCreate("b")
    list.getOrCreate("c")

    val deadline = System.currentTimeMillis() + 2000L
    while (list.size > 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L)
    }

    assertEquals(2, list.size)
    assertFalse(parentJob.isCancelled, "驱逐 borrow 模式 entry 不能 cancel 父 Job")
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
