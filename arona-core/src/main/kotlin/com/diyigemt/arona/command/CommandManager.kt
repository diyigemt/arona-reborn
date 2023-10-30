package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.command.CommandSender.Companion.toCommandSender
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.communication.event.TencentMessageEvent
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.toMessageChain
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import io.ktor.util.logging.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object CommandManager {
  private val logger = KtorSimpleLogger("CommandManager")
  private val modifyLock = ReentrantLock()
  internal val commandMap: MutableMap<String, Command> = mutableMapOf()

  fun matchCommand(commandName: String) = commandMap[commandName.lowercase()]
  fun getRegisteredCommands(owner: CommandOwner): List<Command> = commandMap.values.filter { it.owner == owner }
  fun unregisterAllCommands(owner: CommandOwner) {
    for (registeredCommand in getRegisteredCommands(owner)) {
      unregisterCommand(registeredCommand)
    }
  }

  fun registerCommand(command: Command, override: Boolean): Boolean {
    kotlin.runCatching {
      command.secondaryNames // init lazy
      command.description // init lazy
      command.targetExtensionFunction // init lazy
    }.onFailure {
      throw IllegalStateException("Failed to init command ${command}.", it)
    }

    modifyLock.withLock {
      if (!override) {
        if (command.findDuplicate() != null) return false
      }
      val lowerCaseName = command.name
      commandMap[lowerCaseName] = command
      return true
    }
  }

  fun findDuplicateCommand(command: Command): Command? = commandMap.values.firstOrNull { it.name == command.name }

  fun unregisterCommand(command: Command): Boolean = modifyLock.withLock {
    commandMap.remove(command.name) != null
  }

  fun isCommandRegistered(command: Command): Boolean = matchCommand(command.name) != null

  suspend fun executeCommand(
    caller: CommandSender,
    message: Message,
    checkPermission: Boolean = true,
  ): CommandExecuteResult {
    return executeCommandImpl(message, caller, checkPermission)
  }

  private fun Command.findDuplicate() = findDuplicateCommand(this)

  private val Command.name
    get() = primaryName.lowercase()

  internal fun init() {
    GlobalEventChannel.subscribeAlways<TencentMessageEvent> {
      // 命令必须以 "/" 开头
      val text = it.message.filterIsInstance<PlainText>().firstOrNull() ?: return@subscribeAlways
      val commandText = text.toString()
      if (!commandText.startsWith("/")) {
        return@subscribeAlways
      }
      val result = executeCommand(it.toCommandSender(), it.message)
      // TODO exception print
      if (result !is CommandExecuteResult.Success) {
        result.exception?.let { it1 -> logger.error(it1) }
      }
    }
  }

  @Suppress("NOTHING_TO_INLINE")
  inline fun Command.register(override: Boolean = false): Boolean = registerCommand(this, override)

  @Suppress("NOTHING_TO_INLINE")
  inline fun Command.unregister(): Boolean = unregisterCommand(this)
}

sealed class CommandExecuteResult {
  abstract val exception: Throwable?

  abstract val command: Command?

  /** 指令执行成功 */
  class Success(
    override val command: Command,
  ) : CommandExecuteResult() {
    override val exception: Nothing? get() = null
  }

  abstract class Failure : CommandExecuteResult()

  /** 指令方法调用过程出现了错误 */
  class ExecutionFailed(
    override val exception: Throwable,
    override val command: Command,
  ) : Failure()

  class UnresolvedCommand : Failure() {
    override val exception: Nothing? get() = null
    override val command: Nothing? get() = null
  }

  /** 没有匹配的指令 */
  class Intercepted(
    override val command: Command?,
  ) : Failure() {
    override val exception: Nothing? get() = null
  }

  /** 权限不足 */
  class PermissionDenied(
    /** 尝试执行的指令 */
    override val command: Command,
  ) : Failure() {
    /** 指令执行时发生的错误, 总是 `null` */
    override val exception: Nothing? get() = null
  }

  /** 没有匹配的指令 */
  class UnmatchedSignature(
    /** 尝试执行的指令 */
    override val command: Command,
  ) : Failure() {
    /** 指令执行时发生的错误, 总是 `null` */
    override val exception: Nothing? get() = null
  }
}

internal suspend fun executeCommandImpl(
  message: Message,
  caller: CommandSender,
  checkPermission: Boolean,
): CommandExecuteResult {

  val call = message.toMessageChain()
  val commandStr =
    call.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return CommandExecuteResult.UnresolvedCommand()
  val command = CommandManager.matchCommand(commandStr) ?: return CommandExecuteResult.UnresolvedCommand()
  val arg = call.toString()
  return try {
    (command as CliktCommand).context {
      obj = caller
    }.parse(arg.split(" "))
    CommandExecuteResult.Success(command)
  } catch (e: Throwable) {
    CommandExecuteResult.ExecutionFailed(e, command)
  }
}
