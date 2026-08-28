package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.ConsoleCommandSender
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// 锁住 CommandSignature.instanceFactory 在 contextual 热路径上的关键不变量:
//  - object 命令: 多次调用返回同一 singleton.
//  - class 命令: 多次调用返回不同实例 (天然无跨调用污染).
class CommandSignatureInstanceFactoryTest {

  object FactoryObjectCommand : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "factory-obj-cmd",
  ) {
    suspend fun ConsoleCommandSender.handle() {}
  }

  class FactoryClassCommand : AbstractCommand(
    owner = ConsoleCommandOwner,
    primaryName = "factory-class-cmd",
  ) {
    suspend fun ConsoleCommandSender.handle() {}
  }

  @Test
  fun `object 命令 instanceFactory 返回同一 singleton`() {
    val sig = signatureFor(FactoryObjectCommand::class, "factory-obj-cmd")

    val first = sig.instanceFactory()
    val second = sig.instanceFactory()
    val third = sig.instanceFactory()

    assertSame(FactoryObjectCommand, first, "object 命令必须返回 singleton")
    assertSame(first, second)
    assertSame(second, third)
  }

  @Test
  fun `class 命令 instanceFactory 每次返回新实例`() {
    val sig = signatureFor(FactoryClassCommand::class, "factory-class-cmd")

    val instances = (1..50).map { sig.instanceFactory() }

    assertTrue(instances.all { it::class == FactoryClassCommand::class }, "class 不变")
    // 任意两个相邻实例必须是不同对象 (非 singleton).
    instances.zipWithNext().forEach { (a, b) ->
      assertNotSame(a, b, "class 命令每次必须新建, 否则会导致跨调用 option/argument 状态污染")
    }
  }

  private fun signatureFor(clazz: KClass<out AbstractCommand>, primaryName: String): CommandSignature {
    val fn: KFunction<*> = clazz.declaredMemberExtensionFunctions.first { it.name == "handle" }
    return CommandSignature(
      clazz = clazz,
      children = mutableListOf(),
      childrenMap = mutableMapOf(clazz to fn),
      owner = ConsoleCommandOwner,
      primaryName = primaryName,
      isUnderDevelopment = false,
      targetExtensionFunction = fn,
    )
  }
}
