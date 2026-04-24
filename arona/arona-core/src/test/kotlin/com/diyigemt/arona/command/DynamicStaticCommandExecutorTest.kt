package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.ConsoleCommandSender
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
