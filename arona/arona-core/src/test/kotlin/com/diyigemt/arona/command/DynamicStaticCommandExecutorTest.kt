package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.ConsoleCommandSender
import com.github.ajalt.clikt.core.BaseCliktCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 回归保护: 旧实现传 suspend=true 让 AbstractCommand.run() 走 caller.launch 分支,
// Mutex 只锁到 parse 结束, 业务体在另一个协程里并发跑; 上层 await() 拿到 "启动成功" 而非 "执行完成".
class DynamicStaticCommandExecutorTest {

  object TestStaticCommand : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "static-test-cmd",
  ) {
    suspend fun ConsoleCommandSender.handle() {
      Hooks.runBody()
    }
  }

  object Hooks {
    @Volatile
    var body: suspend () -> Unit = {}
    val active = AtomicInteger(0)
    val maxConcurrency = AtomicInteger(0)
    val completed = AtomicInteger(0)

    suspend fun runBody() {
      val now = active.incrementAndGet()
      maxConcurrency.updateAndGet { old -> max(old, now) }
      try {
        body()
      } finally {
        active.decrementAndGet()
        completed.incrementAndGet()
      }
    }

    fun reset() {
      body = {}
      active.set(0)
      maxConcurrency.set(0)
      completed.set(0)
    }
  }

  @AfterTest
  fun resetHooks() {
    Hooks.reset()
  }

  private fun staticSignature(): CommandSignature {
    val fn: KFunction<*> = TestStaticCommand::class.declaredMemberExtensionFunctions
      .first { it.name == "handle" }
    @Suppress("UNCHECKED_CAST")
    val commandClass = TestStaticCommand::class as KClass<out AbstractCommand>
    return CommandSignature(
      clazz = commandClass,
      children = mutableListOf(),
      childrenMap = mutableMapOf(commandClass to fn),
      owner = ConsoleCommandOwner,
      primaryName = "static-test-cmd",
      isUnderDevelopment = false,
      targetExtensionFunction = fn,
    )
  }

  @Test
  fun `singleton 命令的业务体在并发请求下仍然串行执行`() {
    runBlocking {
      Hooks.body = { delay(30) }
      val executor = DynamicStaticCommandExecutor(
        path = listOf("static-test-cmd"),
        primaryName = "static-test-cmd",
        parentSignature = staticSignature(),
      )

      val results = (1..5).map {
        async { executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await() }
      }.awaitAll()

      withTimeout(5_000) {
        while (Hooks.completed.get() < 5) delay(10)
      }

      assertEquals(5, results.count { it is CommandExecuteResult.Success })
      assertEquals(1, Hooks.maxConcurrency.get(), "业务体应串行, max=${Hooks.maxConcurrency.get()}")
      assertEquals(0, executor.runningWorkers)
    }
  }

  @Test
  fun `多 executor 指向同一 singleton 时旧 executor 仍可正常执行`() {
    // register/unregister / plugin reload / 测试重跑都会触发 DynamicStaticCommandExecutor 重建.
    // 修复后: shared state (Mutex + holder) 按 worker singleton 维度共享, 不会因第二个 executor 的 init
    // 覆盖第一个 executor 的闭包而导致 in-flight 请求 checkNotNull 失败.
    runBlocking {
      Hooks.body = {}
      val executor1 = DynamicStaticCommandExecutor(
        path = listOf("static-test-cmd"),
        primaryName = "static-test-cmd",
        parentSignature = staticSignature(),
      )
      assertTrue(executor1.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await() is CommandExecuteResult.Success)

      val executor2 = DynamicStaticCommandExecutor(
        path = listOf("static-test-cmd"),
        primaryName = "static-test-cmd",
        parentSignature = staticSignature(),
      )
      assertTrue(executor2.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await() is CommandExecuteResult.Success)

      // 关键断言: executor1 在 executor2 创建之后仍然能跑通 (旧 executor 不会因 _contextConfig 被新 executor
      // 覆盖而抛错). 修复前这一行会因 checkNotNull(pendingCallContext) 抛出 IllegalStateException.
      assertTrue(executor1.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await() is CommandExecuteResult.Success)
      assertEquals(3, Hooks.completed.get())
    }
  }

  @Test
  fun `_contextConfig 链长不应随 execute 调用次数累积`() {
    // Clikt 5 的 context() 是累积式 (configureContext 把新闭包套在旧闭包外层).
    // DynamicStaticCommandExecutor 的修复: 构造期一次性挂载, 之后 _contextConfig 引用必须稳定.
    runBlocking {
      Hooks.body = {}
      val executor = DynamicStaticCommandExecutor(
        path = listOf("static-test-cmd"),
        primaryName = "static-test-cmd",
        parentSignature = staticSignature(),
      )

      // 跑首次, 让 worker 完成初始化 + 触发一次 resetContext, 拿到稳定状态下的引用.
      executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await()
      val initial = readContextConfig(TestStaticCommand)

      repeat(1000) {
        executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await()
      }

      val after = readContextConfig(TestStaticCommand)
      assertSame(
        initial,
        after,
        "_contextConfig 引用应稳定: 出现新对象意味着 configureContext 被重复调用, 链长会随调用次数线性增长",
      )
      assertEquals(1001, Hooks.completed.get())
    }
  }

  private fun readContextConfig(cmd: BaseCliktCommand<*>): Any {
    var cls: Class<*>? = BaseCliktCommand::class.java
    while (cls != null) {
      cls.declaredFields.firstOrNull { it.name == "_contextConfig" }?.let { f ->
        f.isAccessible = true
        return f.get(cmd)!!
      }
      cls = cls.superclass
    }
    error("BaseCliktCommand._contextConfig 字段反射失败 (Clikt 升级后改名?)")
  }

  @Test
  fun `await 必须等到业务体真正执行完毕才返回`() {
    runBlocking {
      val gate = CompletableDeferred<Unit>()
      Hooks.body = { gate.await() }
      val executor = DynamicStaticCommandExecutor(
        path = listOf("static-test-cmd"),
        primaryName = "static-test-cmd",
        parentSignature = staticSignature(),
      )

      val deferred = executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false)
      // 给业务体足够时间进入 gate.await(); 此时 deferred 不应完成.
      withTimeout(5_000) {
        while (Hooks.active.get() < 1) delay(10)
      }
      assertFalse(deferred.isCompleted, "deferred should be pending while body is still awaiting gate")

      gate.complete(Unit)
      assertTrue(deferred.await() is CommandExecuteResult.Success)
      assertEquals(1, Hooks.completed.get())
    }
  }
}
