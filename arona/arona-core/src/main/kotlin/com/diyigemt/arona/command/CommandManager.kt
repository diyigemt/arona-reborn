@file:Suppress("MemberVisibilityCanBePrivate", "unused_parameter")

package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.*
import com.diyigemt.arona.communication.command.CommandSender.Companion.toCommandSender
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentAt.Companion.toReadableTencentAt
import com.diyigemt.arona.communication.message.TencentAt.Companion.toSourceTencentAt
import com.diyigemt.arona.communication.message.toMessageChain
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.util.logging.*
import kotlinx.coroutines.withTimeout
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

  @Suppress("NOTHING_TO_INLINE")
  inline fun Command.register(override: Boolean = false): Boolean = registerCommand(this, override)

  @Suppress("NOTHING_TO_INLINE")
  inline fun Command.unregister(): Boolean = unregisterCommand(this)
}

sealed class CommandExecuteResult {
  abstract val exception: Throwable?

  abstract val command: AbstractCommand?

  /** 指令执行成功 */
  class Success(
    override val command: AbstractCommand,
  ) : CommandExecuteResult() {
    override val exception: Nothing? get() = null
  }

  abstract class Failure : CommandExecuteResult()

  /** 指令方法调用过程出现了错误 */
  class ExecutionFailed(
    override val exception: Throwable,
    override val command: AbstractCommand,
  ) : Failure()

  class UnresolvedCommand : Failure() {
    override val exception: Nothing? get() = null
    override val command: Nothing? get() = null
  }

  /** 没有匹配的指令 */
  class Intercepted(
    override val command: AbstractCommand?,
  ) : Failure() {
    override val exception: Nothing? get() = null
  }

  /** 权限不足 */
  class PermissionDenied(
    /** 尝试执行的指令 */
    override val command: AbstractCommand,
  ) : Failure() {
    /** 指令执行时发生的错误, 总是 `null` */
    override val exception: Nothing? get() = null
  }

  /** 参数不匹配 */
  class UnmatchedSignature(
    override val exception: Throwable,
    override val command: AbstractCommand,
  ) : Failure()
}

internal val commandTerminal = Terminal(ansiLevel = AnsiLevel.NONE, interactive = false)
internal val crsiveLocalization = object : Localization {
  override fun usageError() = "错误:"
  override fun usageTitle() = "用例:"
  override fun optionsTitle() = "可选参数"
  override fun optionsMetavar() = "可选参数"
  override fun missingArgument(paramName: String) = "缺少参数 $paramName"
}

internal suspend fun executeCommandImpl(
  message: Message,
  caller: CommandSender,
  checkPermission: Boolean,
): CommandExecuteResult {

  val call = message.toMessageChain()
  val messageString =
    call.filterIsInstance<PlainText>().firstOrNull()?.toString() ?: return CommandExecuteResult.UnresolvedCommand()
  val commandStr =
    messageString.split(" ").toMutableList().removeFirstOrNull() ?: return CommandExecuteResult.UnresolvedCommand()
  val command =
    CommandManager.matchCommand(commandStr.replace("/", "")) as? AbstractCommand ?: return CommandExecuteResult
      .UnresolvedCommand()
  val arg = call.toString()
  return runCatching {
    command.context {
      obj = caller
      terminal = commandTerminal
      localization = crsiveLocalization
    }.parse(
      arg
        .split(" ")
        .toMutableList()
        .apply {
          // 如果第一个是at机器人, 继续移除掉
          removeFirstOrNull()
            ?.let { it.toReadableTencentAt() ?: it.toSourceTencentAt() }
            .also {
              if (it != null) {
                removeFirstOrNull()
              }
            }
        }
    )
    CommandExecuteResult.Success(command)
  }.getOrElse {
    when (it) {
      is MissingArgument -> CommandExecuteResult.UnmatchedSignature(it, command)
      else -> CommandExecuteResult.ExecutionFailed(it, command)
    }
  }
}


suspend inline fun GuildUserCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend GuildUserCommandSender.(TencentGuildPrivateMessageEvent) -> Boolean = { true },
  noinline action: suspend GuildUserCommandSender.(TencentGuildPrivateMessageEvent) -> Unit,
) {
  val mapper = createMapper<GuildUserCommandSender, TencentGuildPrivateMessageEvent>(filter)
  val event = (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentGuildPrivateMessageEvent, TencentGuildPrivateMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentGuildPrivateMessageEvent, TencentGuildPrivateMessageEvent>(mapper)
    }
  })
  action.invoke(event.toCommandSender(), event)
}

suspend inline fun GuildChannelCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend GuildChannelCommandSender.(TencentGuildMessageEvent) -> Boolean = { true },
  noinline action: suspend GuildChannelCommandSender.(TencentGuildMessageEvent) -> Unit,
) {
  val mapper = createMapper<GuildChannelCommandSender, TencentGuildMessageEvent>(filter)
  val event = (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentGuildMessageEvent, TencentGuildMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentGuildMessageEvent, TencentGuildMessageEvent>(mapper)
    }
  })
  action.invoke(event.toCommandSender(), event)
}


suspend inline fun <reified C : UserCommandSender> C.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend C.(C) -> Boolean = { true },
  noinline action: suspend C.(C) -> Unit,
) {
  val mapper = when (this) {
    is SingleUserCommandSender -> TODO()
    is GroupCommandSender -> TODO()
    is GuildUserCommandSender -> createMapper<C, TencentGuildPrivateMessageEvent>(filter)
    is GuildChannelCommandSender -> createMapper<C, TencentGuildMessageEvent>(filter)
    else -> TODO()
  }

  val sender = (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent(mapper)
    }
  }).toCommandSender() as C

  action.invoke(sender, sender)
}

@PublishedApi
@JvmName("\$createMapper")
internal inline fun <reified C : UserCommandSender, E : TencentMessageEvent> C.createMapper(
  crossinline filter: suspend C.(C) -> Boolean,
): suspend (E) -> E? =
  mapper@{ event ->
    if (!this.isContextIdenticalWith(event)) return@mapper null
    if (!filter(this, this)) return@mapper null
    event
  }

@PublishedApi
internal inline fun <reified C : UserCommandSender, E : TencentMessageEvent> C.createMapper(
  crossinline filter: suspend C.(E) -> Boolean,
): suspend (E) -> E? =
  mapper@{ event ->
    if (!this.isContextIdenticalWith(event)) return@mapper null
    if (!filter(this, event)) return@mapper null
    event
  }

fun UserCommandSender.isContextIdenticalWith(event: TencentMessageEvent): Boolean {
  return this.user.id == event.sender.id && this.subject.id == event.subject.id
}
