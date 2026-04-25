package com.diyigemt.arona.command

import com.diyigemt.arona.command.CommandManager.register
import com.diyigemt.arona.command.CommandManager.unregister
import com.diyigemt.arona.communication.command.ConsoleCommandSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Sprint 3.4 锁住 CommandManager 注册容器在并发场景的语义:
//  - commandMap 换 CHM 后, 同名命令的并发 register (override=false) 必须只有 1 成功.
//  - register 同时维护 ExecutorMap (新增的 register/unregisterExecutorsFor 联动), 不能再依赖
//    initExecutorMap 启动期重建.
//  - key 写入与读出统一 lowercase, 修旧实现 register=primaryName / match=primaryName.lowercase() 的不一致.
class CommandManagerConcurrencyTest {

  object Sprint34CmdAlpha : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "sprint34-alpha",
  ) {
    suspend fun ConsoleCommandSender.handle() {}
  }

  object Sprint34CmdBeta : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "sprint34-beta",
  ) {
    suspend fun ConsoleCommandSender.handle() {}
  }

  object Sprint34CmdGamma : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "Sprint34-MixedCase",
  ) {
    suspend fun ConsoleCommandSender.handle() {}
  }

  private val testCommands = listOf(Sprint34CmdAlpha, Sprint34CmdBeta, Sprint34CmdGamma)

  @AfterTest
  fun cleanup() {
    testCommands.forEach { it.unregister() }
  }

  @Test
  fun `register 后 commandMap 与 ExecutorMap 同步可见`() {
    assertTrue(Sprint34CmdAlpha.register(), "首次 register 必须返回 true")
    assertNotNull(CommandManager.matchCommand("sprint34-alpha"), "matchCommand 必须命中")
    assertNotNull(ExecutorMap[Sprint34CmdAlpha.primaryName], "ExecutorMap 必须同步包含 executor")
  }

  @Test
  fun `unregister 后 commandMap 与 ExecutorMap 同步清理`() {
    Sprint34CmdAlpha.register()
    assertNotNull(ExecutorMap[Sprint34CmdAlpha.primaryName])

    assertTrue(Sprint34CmdAlpha.unregister(), "unregister 必须返回 true")
    assertNull(CommandManager.matchCommand("sprint34-alpha"), "match 不应再命中")
    assertNull(ExecutorMap[Sprint34CmdAlpha.primaryName], "ExecutorMap 必须同步清理")
  }

  @Test
  fun `unregister 未注册的命令应返回 false 不抛`() {
    assertFalse(Sprint34CmdAlpha.unregister(), "未注册时应返回 false")
  }

  @Test
  fun `register override false 同名重复返回 false 不替换`() {
    assertTrue(Sprint34CmdAlpha.register())
    val executorBefore = ExecutorMap[Sprint34CmdAlpha.primaryName]
    assertFalse(Sprint34CmdAlpha.register(), "同名 override=false 必须返回 false")
    val executorAfter = ExecutorMap[Sprint34CmdAlpha.primaryName]
    assertTrue(executorBefore === executorAfter, "ExecutorMap 不应被替换为新对象")
  }

  @Test
  fun `register override true 替换旧 signature 与 executor`() {
    assertTrue(Sprint34CmdAlpha.register())
    val executorBefore = ExecutorMap[Sprint34CmdAlpha.primaryName]
    assertTrue(Sprint34CmdAlpha.register(override = true), "override=true 必须返回 true")
    val executorAfter = ExecutorMap[Sprint34CmdAlpha.primaryName]
    assertNotNull(executorAfter)
    assertTrue(executorBefore !== executorAfter, "override=true 应替换 ExecutorMap entry")
  }

  @Test
  fun `commandMap key lowercase 统一 register 写入与 match 读出大小写一致`() {
    // 旧实现 register 写 instance.primaryName, match 读 commandName.lowercase, 大小写不对称导致
    // 注册了 mixed-case 名字的命令永远 match 不到. 修复后任何大小写都能命中.
    assertTrue(Sprint34CmdGamma.register())
    assertNotNull(CommandManager.matchCommand("sprint34-mixedcase"), "全小写应命中")
    assertNotNull(CommandManager.matchCommand("Sprint34-MixedCase"), "原始大小写应命中")
    assertNotNull(CommandManager.matchCommand("SPRINT34-MIXEDCASE"), "全大写应命中")
  }

  @Test
  fun `100 并发 register 同名命令 override false 只有 1 成功`() {
    runBlocking {
      val results = coroutineScope {
        (1..100).map {
          async(Dispatchers.Default) {
            Sprint34CmdAlpha.register(override = false)
          }
        }.awaitAll()
      }
      assertEquals(1, results.count { it }, "并发 register 同名只能有 1 个赢家")
      assertEquals(99, results.count { !it }, "其余 99 个必须返回 false")
      assertNotNull(ExecutorMap[Sprint34CmdAlpha.primaryName], "winner 注册的 executor 应存在")
    }
  }

  @Test
  fun `并发 register 不同命令全部成功`() {
    // 三个不同名命令并发 register, 容器层不应 race, 全部成功.
    runBlocking {
      val results = coroutineScope {
        testCommands.map { cmd ->
          async(Dispatchers.Default) { cmd.register(override = false) }
        }.awaitAll()
      }
      assertTrue(results.all { it }, "并发 register 不同名命令全应成功")
      testCommands.forEach { cmd ->
        assertNotNull(
          CommandManager.matchCommand(cmd.primaryName.lowercase()),
          "${cmd.primaryName} 应可被 match",
        )
      }
    }
  }
}
