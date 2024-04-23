package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.AbstractCommandSender
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmName

interface Command {
  val primaryName: String
  val secondaryNames: Array<out String>
  val description: String
  val owner: CommandOwner
  val permission: Permission

  companion object {
    fun checkCommandName(name: String) {
      when {
        name.isBlank() -> throw IllegalArgumentException("Command name should not be blank.")
        name.any { it.isWhitespace() } -> throw IllegalArgumentException("Spaces are not yet allowed in command name.")
        name.contains(':') -> throw IllegalArgumentException("':' is forbidden in command name.")
        name.contains('.') -> throw IllegalArgumentException("'.' is forbidden in command name.")
      }
    }
  }
}

internal val ILLEGAL_SUB_NAME_CHARS = "\\/!@#$%^&*()_+-={}[];':\",.<>?`~".toCharArray()

class IllegalCommandDeclarationException : Exception {
  override val message: String?

  constructor(
    ownerCommand: KClass<*>,
    correspondingCommand: KClass<*>,
    message: String?,
  ) : super("Illegal command declaration: ${correspondingCommand.qualifiedName} declared in ${ownerCommand.qualifiedName}") {
    this.message = message
  }

  constructor(
    ownerCommand: KClass<*>,
    message: String?,
  ) : super("Illegal command declaration: ${ownerCommand.qualifiedName}") {
    this.message = message
  }
}

internal class CommandReflector(
  val command: KClass<out Command>,
) {
  private fun getSubCommandNames(command: KClass<out Command>): String {
    val annotated = command.findAnnotation<SubCommand>()!!.value
    return annotated.ifEmpty { command.simpleName ?: command.qualifiedName ?: command.jvmName }
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun KClass<*>.illegalDeclaration(
    correspondingCommand: KClass<*>,
    message: String,
  ): Nothing {
    throw IllegalCommandDeclarationException(command, correspondingCommand, message)
  }

  private fun KClass<*>.isSubCommand() = isSubclassOf(AbstractCommand::class) && this.hasAnnotation<SubCommand>()

  private fun KClass<out Command>.checkNames() {
    val name = getSubCommandNames(this)
    ILLEGAL_SUB_NAME_CHARS.find { it in name }?.let {
      this.illegalDeclaration(this, "'$it' is forbidden in command name.")
    }
  }

  private fun KClass<*>.checkModifiers() {
    if (objectInstance != null) illegalDeclaration(this, "SubCommand should not be object")
    if (visibility == KVisibility.PRIVATE) illegalDeclaration(
      this, "Command function must be accessible, that is, " +
          "effectively public."
    )
    if (this.hasAnnotation<JvmStatic>()) illegalDeclaration(this, "Command function must not be static.")

    // should we allow abstract?

    // if (isAbstract) illegalDeclaration("Command function cannot be abstract")
  }

  @Suppress("UNCHECKED_CAST")
  fun findSubCommand(): List<KClass<out AbstractCommand>> {
    val s = command.nestedClasses
      .asSequence()
      .filter { it.isSubCommand() } as Sequence<KClass<out AbstractCommand>>
    return s
      .onEach { it.checkModifiers() }
      .onEach { it.checkNames() }
      .toList()
  }

  fun findTargetExtensionFunction() = command.declaredMemberExtensionFunctions.first {
    it.extensionReceiverParameter!!.type.isSubtypeOf(CommandSender::class.starProjectedType)
        && it.parameters.size == 2
  }
}

/**
 * 标记一个开发中的指令对象
 */
@Target(AnnotationTarget.CLASS)
annotation class UnderDevelopment

/**
 * 标记一个子指令对象
 */
@Target(AnnotationTarget.CLASS)
annotation class SubCommand(
  val value: String = "",
  val forClass: KClass<out AbstractCommand> = AbstractCommand::class,
)

abstract class AbstractCommand(
  final override val owner: CommandOwner,
  final override val primaryName: String,
  final override val secondaryNames: Array<out String> = arrayOf(),
  override val description: String = "<no description available>",
  help: String = "",
) : CliktCommand(name = primaryName, help = help, epilog = description, invokeWithoutSubcommand = true), Command {
  private val workerName by requireObject<String>("worker")
  private val root by requireObject<AbstractCommand>("root")
  private val caller by requireObject<AbstractCommandSender>("caller")
  private val signature by requireObject<CommandSignature>("signature")

  init {
    Command.checkCommandName(primaryName)
    secondaryNames.forEach(Command.Companion::checkCommandName)
  }

  final override fun run() {
    val fn = signature.childrenMap[this@AbstractCommand::class]!!
    if (!caller.kType.isSubtypeOf(fn.parameters[1].type)) {
      return
    }
    runBlocking(caller.coroutineContext) {
      fn.callSuspend(this@AbstractCommand, caller)
    }
  }

  final override val permission = findOrCreateCommandPermission(owner.permission)

  companion object {
    private fun findTargetExtensionFunction(
      root: CommandSignature,
      target: KClass<out AbstractCommand>
    ): KFunction<*>? {
      if (root.clazz == target) {
        return root.targetExtensionFunction
      }
      return root.children.firstNotNullOfOrNull { findTargetExtensionFunction(it, target) }
    }
  }
}

internal fun Command.findOrCreateCommandPermission(parent: Permission): Permission {
  val id = owner.permissionId("command.$primaryName")
  return PermissionService[id] ?: PermissionService.register(id, description, parent)
}
