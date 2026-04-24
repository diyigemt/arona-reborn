package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.ConsoleCommandSender
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 回归保护: 旧实现 commitWorker 先 addLast 再 launch start, 两步之间新请求可以抢走 worker,
// lazy task 启动后 workerPool.removeFirst() 抛 NoSuchElementException.
// 并且命令在 receive() 处被取消时, 旧实现也无法正确回滚 pending / running / idle 计数.
class DynamicContextualCommandExecutorTest {

  @Suppress("unused")
  class QueueTestCommand : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "queue-test-cmd",
  ) {
    suspend fun ConsoleCommandSender.handle() {
      Hooks.started.incrementAndGet()
      Hooks.body()
    }
  }

  private object Hooks {
    @Volatile var body: suspend () -> Unit = {}
    val started = atomic(0)
  }

  private fun queueTestSignature(): CommandSignature {
    val fn: KFunction<*> = QueueTestCommand::class.declaredMemberExtensionFunctions.first { it.name == "handle" }
    @Suppress("UNCHECKED_CAST")
    val commandClass = QueueTestCommand::class as KClass<out AbstractCommand>
    return CommandSignature(
      clazz = commandClass,
      children = mutableListOf(),
      childrenMap = mutableMapOf(commandClass to fn),
      owner = ConsoleCommandOwner,
      primaryName = "queue-test-cmd",
      isUnderDevelopment = false,
      targetExtensionFunction = fn,
    )
  }

  @AfterTest
  fun reset() {
    Hooks.body = {}
    Hooks.started.value = 0
  }

  @Test
  fun `排队执行完毕后 worker 全部归还不会丢失`() {
    runBlocking {
      Hooks.body = { delay(30) }

      val executor = DynamicContextualCommandExecutor(
        path = listOf("queue-test-cmd"),
        primaryName = "queue-test-cmd",
        parentSignature = queueTestSignature(),
        initCapacity = 1, // workerPoolCapacity = 2
      )

      val results = (1..8).map {
        async { executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false).await() }
      }.awaitAll()

      assertEquals(8, results.size)
      assertTrue(results.all { it is CommandExecuteResult.Success }, "all commands should succeed")
      assertEquals(8, Hooks.started.value)
      assertEquals(0, executor.runningWorkers)
      assertEquals(0, executor.pendingTasks)
      assertEquals(2, executor.idleWorkers)
    }
  }

  @Test
  fun `等待中的任务被取消后不会泄漏 pendingTasks 或 worker`() {
    runBlocking {
      val gate = CompletableDeferred<Unit>()
      Hooks.body = { gate.await() }

      val executor = DynamicContextualCommandExecutor(
        path = listOf("queue-test-cmd"),
        primaryName = "queue-test-cmd",
        parentSignature = queueTestSignature(),
        initCapacity = 1,
      )

      // 先占满 workerPoolCapacity = 2 个 worker, 它们都卡在 gate.await() 上.
      val running1 = executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false)
      val running2 = executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false)
      withTimeout(5_000) {
        while (Hooks.started.value < 2) delay(10)
      }

      // 第 3 个请求会卡在 workerPool.receive() 上, pendingTasks 应 +1.
      val waiting = executor.execute(emptyList(), ConsoleCommandSender, checkPermission = false)
      withTimeout(5_000) {
        while (executor.pendingTasks < 1) delay(10)
      }
      assertEquals(2, executor.runningWorkers)
      assertEquals(0, executor.idleWorkers)
      assertEquals(1, executor.pendingTasks)

      waiting.cancel()
      // cancel 会让 receive() 抛 CancellationException, finally 回滚 pendingCounter.
      withTimeout(5_000) {
        while (executor.pendingTasks != 0) delay(10)
      }
      assertEquals(0, executor.pendingTasks)
      // 此时 worker 还没被 waiting 拿走, running 保持 2.
      assertEquals(2, executor.runningWorkers)

      // 放行两个正在运行的任务, 它们归还 worker 后 idle 应恢复到 2.
      gate.complete(Unit)
      assertTrue(running1.await() is CommandExecuteResult.Success)
      assertTrue(running2.await() is CommandExecuteResult.Success)
      withTimeout(5_000) {
        while (executor.idleWorkers != 2) delay(10)
      }
      assertEquals(0, executor.runningWorkers)
      assertEquals(0, executor.pendingTasks)
      assertEquals(2, executor.idleWorkers)
    }
  }
}
