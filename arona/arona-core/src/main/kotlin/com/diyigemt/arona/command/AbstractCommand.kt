package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.AbstractCommandSender
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
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
  val targetExtensionFunction: KFunction<*>
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
  val command: Command,
) {
  private fun getSubCommandNames(command: Command): String {
    val annotated = command::class.findAnnotation<SubCommand>()!!.value
    return annotated.ifEmpty { command::class.simpleName ?: command::class.qualifiedName ?: command::class.jvmName }
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline fun KClass<*>.illegalDeclaration(
    correspondingCommand: KClass<*>,
    message: String,
  ): Nothing {
    throw IllegalCommandDeclarationException(command::class, correspondingCommand, message)
  }

  private fun KClass<*>.isSubCommand() = isSubclassOf(AbstractCommand::class) && this.hasAnnotation<SubCommand>()

  private fun Command.checkNames() {
    val name = getSubCommandNames(this)
    ILLEGAL_SUB_NAME_CHARS.find { it in name }?.let {
      this::class.illegalDeclaration(this::class, "'$it' is forbidden in command name.")
    }
  }

  private fun KClass<*>.checkModifiers() {
    if (objectInstance == null) illegalDeclaration(this, "SubCommand should be object")
    if (visibility == KVisibility.PRIVATE) illegalDeclaration(
      this, "Command function must be accessible, that is, " +
        "effectively public."
    )
    if (this.hasAnnotation<JvmStatic>()) illegalDeclaration(this, "Command function must not be static.")

    // should we allow abstract?

    // if (isAbstract) illegalDeclaration("Command function cannot be abstract")
  }

  @Suppress("UNCHECKED_CAST")
  fun findSubCommand(): List<AbstractCommand> {
    val s = command::class.nestedClasses
      .asSequence()
      .filter { it.isSubCommand() } as Sequence<KClass<out AbstractCommand>>
    return s
      .onEach { it.checkModifiers() }
      .map { it.objectInstance!! }
      .onEach { it.checkNames() }
      .toList()
  }

  fun findTargetExtensionFunction() = command::class.declaredMemberExtensionFunctions.first {
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
  private val commandSender by requireObject<AbstractCommandSender>()
  private val reflector by lazy {
    CommandReflector(this)
  }
  override val targetExtensionFunction by lazy {
    reflector.findTargetExtensionFunction()
  }

  init {
    Command.checkCommandName(primaryName)
    secondaryNames.forEach(Command.Companion::checkCommandName)
    runCatching {
      subcommands(reflector.findSubCommand())
    }.onFailure {
      throw it
    }
  }

  final override fun run() {
    if (!commandSender.kType.isSubtypeOf(targetExtensionFunction.parameters[1].type)) {
      return
    }
    runBlocking(commandSender.coroutineContext) {
      targetExtensionFunction.callSuspend(this@AbstractCommand, commandSender)
    }
  }

  final override val permission = findOrCreateCommandPermission(owner.permission)
}

internal fun Command.findOrCreateCommandPermission(parent: Permission): Permission {
  val id = owner.permissionId("command.$primaryName")
  return PermissionService[id] ?: PermissionService.register(id, description, parent)
}
