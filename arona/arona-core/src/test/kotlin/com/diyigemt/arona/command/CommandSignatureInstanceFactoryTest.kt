package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.ConsoleCommandSender
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

// 锁住 CommandSignature.instanceFactory 在 contextual 热路径上的关键不变量:
//  - object 命令: 多次调用返回同一 singleton.
//  - class 命令: 多次调用返回不同实例 (天然无跨调用污染), 但反射 ctor 仅查找一次.
//  - 与 KClass.createInstance() 行为等价 (生成的实例可挂载子命令).
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

  @Test
  fun `instanceFactory 行为与 KClass createInstance 等价`() {
    val sig = signatureFor(FactoryClassCommand::class, "factory-class-cmd")

    // createInstance() 是当前实现的 ground truth, factory 是缓存版本; 两条路径生成的实例
    // 在 class / primaryName / owner 维度必须完全一致.
    val viaFactory = sig.instanceFactory()
    val viaCreateInstance = FactoryClassCommand::class.createObjectOrInstance()

    assertTrue(viaFactory::class == viaCreateInstance::class)
    assertTrue(viaFactory.primaryName == viaCreateInstance.primaryName)
    assertTrue(viaFactory.owner === viaCreateInstance.owner)
  }

  @Test
  fun `instanceFactory 调用 1000 次稳态不抛错`() {
    // 锁住稳态可调用性: 反复访问 lazy property 在 PUBLICATION 模式下保持稳定, 不抛错.
    // 注: 该测试不能证明 ctor 反射查找仅命中一次 (lazy 缓存语义由 Kotlin 标准库保证, 不在此测试范围).
    val sig = signatureFor(FactoryClassCommand::class, "factory-class-cmd")
    repeat(1000) {
      runCatching { sig.instanceFactory() }.getOrElse { fail("第 $it 次 factory 调用失败: ${it::class.simpleName}") }
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
