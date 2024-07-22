@file:Suppress("MemberVisibilityCanBePrivate", "unused_parameter")

package com.diyigemt.arona.command

import com.diyigemt.arona.command.CommandManager.commandMap
import com.diyigemt.arona.communication.command.*
import com.diyigemt.arona.communication.event.*
import com.diyigemt.arona.communication.message.Message
import com.diyigemt.arona.communication.message.PlainText
import com.diyigemt.arona.communication.message.TencentAt.Companion.toReadableTencentAt
import com.diyigemt.arona.communication.message.TencentAt.Companion.toSourceTencentAt
import com.diyigemt.arona.communication.message.toMessageChain
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.Localization
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.util.logging.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.hasAnnotation

internal data class CommandSignature(
  val clazz: KClass<out AbstractCommand>,
  val children: MutableList<CommandSignature>,
  val childrenMap: MutableMap<KClass<out AbstractCommand>, KFunction<*>>,
  val owner: CommandOwner,
  val primaryName: String,
  val isUnderDevelopment: Boolean,
  val targetExtensionFunction: KFunction<*>,
)

@Suppress("NOTHING_TO_INLINE")
inline fun <T : Any> KClass<T>.createObjectOrInstance() = objectInstance ?: createInstance()

internal fun CommandSignature.createInstance(): AbstractCommand {
  val instance = clazz.createObjectOrInstance()
  children.forEach {
    it.createInstance(instance)
  }
  return instance
}

internal fun CommandSignature.createInstance(parent: AbstractCommand): AbstractCommand {
  val instance = clazz.createObjectOrInstance()
  parent.subcommands(instance)
  children.forEach {
    it.createInstance(instance)
  }
  return instance
}

internal fun buildDynamicExecutor(root: CommandSignature, current: CommandSignature, path: List<String>) {
  val newPath = path + listOf(current.primaryName)
  val primaryName = newPath.joinToString(",")
  ExecutorMap[primaryName] = DynamicContextualCommandExecutor(newPath, primaryName, root)
  current.children.forEach {
    buildDynamicExecutor(root, it, newPath)
  }
}

internal fun initExecutorMap() {
  commandMap.forEach { (_, u) ->
    val primaryName = u.primaryName
    if (u.clazz.objectInstance != null) {
      ExecutorMap[primaryName] = DynamicStaticCommandExecutor(listOf(u.primaryName), primaryName, u)
    } else {
      ExecutorMap[primaryName] = DynamicContextualCommandExecutor(listOf(u.primaryName), primaryName, u)
    }
    u.children.forEach {
      buildDynamicExecutor(u, it, listOf(u.primaryName))
    }
  }
}

internal val ExecutorMap: MutableMap<String, DynamicCommandExecutor> = mutableMapOf()

internal fun CommandSignature.matchChildPath(path: List<String>): List<String> {
  if (path.isEmpty()) return listOf(primaryName)
  if (path.size == 1) {
    return if (this.children.any { it.primaryName == path[0] })
      listOf(primaryName) + path
    else
      listOf(primaryName)
  }
  val result = mutableListOf(primaryName)
  var parentSignature = this
  for (it in path) {
    val tmp = parentSignature.children.firstOrNull { c -> c.primaryName == it } ?: break
    result.add(tmp.primaryName)
    parentSignature = tmp
  }
  return result
}

internal fun CommandSignature.flat(): Map<KClass<out AbstractCommand>, KFunction<*>> {
  if (children.isEmpty()) {
    return mapOf(clazz to targetExtensionFunction)
  }
  val map = mutableMapOf(clazz to targetExtensionFunction)
  children.forEach {
    map.putAll(it.flat())
  }
  return map
}

internal fun KClass<out AbstractCommand>.createSignature(): CommandSignature {
  return kotlin.run {
    val instance = createObjectOrInstance()
    val reflector = CommandReflector(this)
    val subCommands = reflector.findSubCommand().map { it.createSignature() }.toMutableList()
    val map = mutableMapOf(this to reflector.findTargetExtensionFunction())
    subCommands.forEach {
      map.putAll(it.flat())
    }
    CommandSignature(
      this,
      subCommands,
      map,
      instance.owner,
      instance.primaryName,
      hasAnnotation<UnderDevelopment>(),
      reflector.findTargetExtensionFunction()
    )
  }
}

object CommandManager {
  private val logger = KtorSimpleLogger("CommandManager")
  private val modifyLock = ReentrantLock()
  internal val commandMap: MutableMap<String, CommandSignature> = mutableMapOf()

  fun matchCommand(commandName: String): Command? = commandMap[commandName.lowercase()]?.createInstance()
  fun matchCommandName(commandName: String): String? = commandMap[commandName.lowercase()]?.primaryName
  internal fun matchCommandSignature(commandName: String): CommandSignature? = commandMap[commandName.lowercase()]
  fun getRegisteredCommands(): List<Command> = commandMap
    .values
    .filter {
      !it.isUnderDevelopment
    }
    .map { it.createInstance() }
    .toList()

  fun getRegisteredCommands(owner: CommandOwner): List<Command> = getRegisteredCommands()
    .filter {
      it.owner == owner
    }

  internal fun internalGetRegisteredCommands(owner: CommandOwner): List<CommandSignature> =
    commandMap
      .values
      .filter {
        it.owner == owner
      }
      .toList()

  fun unregisterAllCommands(owner: CommandOwner) {
    for (registeredCommand in getRegisteredCommands(owner)) {
      unregisterCommand(registeredCommand)
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun registerCommand(command: Command, override: Boolean): Boolean {
    return registerCommandSignature(command::class as KClass<AbstractCommand>, override)
  }

  internal fun registerCommandSignature(command: KClass<out AbstractCommand>, override: Boolean): Boolean {
    return kotlin.runCatching {
      val instance = command.createObjectOrInstance()
      if (!override && findDuplicateCommand(instance) != null) {
        return false
      }
      val signature = command.createSignature()
      return@runCatching modifyLock.withLock {
        val lowerCaseName = instance.primaryName
        commandMap[lowerCaseName] = signature
        true
      }
    }.getOrThrow()
  }

  fun findDuplicateCommand(command: Command): Command? = commandMap
    .values
    .firstOrNull { it.primaryName == command.primaryName }
    ?.createInstance()

  fun unregisterCommand(command: Command): Boolean = modifyLock.withLock {
    commandMap.remove(command.primaryName) != null
  }

  fun isCommandRegistered(command: Command): Boolean = matchCommandName(command.primaryName) != null

  suspend fun executeCommand(
    caller: CommandSender,
    message: Message,
    checkPermission: Boolean = true,
  ): Deferred<CommandExecuteResult> {
    return executeCommandImpl(message, caller, checkPermission)
  }

  private val CommandSignature.name
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
  override fun missingArgument(paramName: String) = "缺少参数: $paramName"
  override fun extraArgumentOne(name: String) = "多余的参数: $name"
  override fun extraArgumentMany(name: String, count: Int) = "多余的参数: $name"
  override fun invalidChoice(choice: String, choices: List<String>) =
    "参数无效:$choice, 可选值为: ${choices.joinToString(",")}"

  override fun badParameterWithMessageAndParam(paramName: String, message: String) = "$paramName 的值无效. $message"
  override fun noSuchSubcommand(name: String, possibilities: List<String>): String {
    return "<$name>子指令 不存在, 是否想要执行: ${possibilities.joinToString(", ") { it }}"
  }
}

internal suspend fun executeCommandImpl(
  message: Message,
  caller: CommandSender,
  checkPermission: Boolean,
): Deferred<CommandExecuteResult> {

  val call = message.toMessageChain()

  val messageString = call
    .filterIsInstance<PlainText>()
    .firstOrNull()?.toString() ?: return coroutineScope {
    async {
      CommandExecuteResult.UnresolvedCommand()
    }
  }
  val commandStr = messageString
    .split(" ")
    .toMutableList()
    .removeFirstOrNull() ?: return coroutineScope {
    async {
      CommandExecuteResult.UnresolvedCommand()
    }
  }
  val commandSignature = CommandManager
    .matchCommandSignature(
      commandStr.replaceFirst("/", "")
    ) ?: return coroutineScope {
    async {
      CommandExecuteResult.UnresolvedCommand()
    }
  }
  val arg = call.toString()
  val parseArg = arg
    .split(" ")
    .filter { it.isNotEmpty() }
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
  // 过滤参数

  val primaryName = commandSignature.matchChildPath(parseArg).joinToString(",")
  val executor = ExecutorMap[primaryName] ?: return coroutineScope {
    async {
      CommandExecuteResult.UnresolvedCommand()
    }
  }
  return executor.execute(parseArg, caller, checkPermission)
}

suspend fun GuildUserCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  filter: suspend GuildUserCommandSender.(TencentGuildPrivateMessageEvent) -> Boolean = { true },
): TencentGuildPrivateMessageEvent {
  val mapper = createMapper<GuildUserCommandSender, TencentGuildPrivateMessageEvent>(filter)
  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentGuildPrivateMessageEvent, TencentGuildPrivateMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentGuildPrivateMessageEvent, TencentGuildPrivateMessageEvent>(mapper)
    }
  }).also {
    setSourceId(it.message.sourceId)
  }
}

suspend fun GuildChannelCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  filter: suspend GuildChannelCommandSender.(TencentGuildMessageEvent) -> Boolean = { true },
): TencentGuildMessageEvent {
  val mapper = createMapper<GuildChannelCommandSender, TencentGuildMessageEvent>(filter)
  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentGuildMessageEvent, TencentGuildMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentGuildMessageEvent, TencentGuildMessageEvent>(mapper)
    }
  }).also {
    setSourceId(it.message.sourceId)
  }
}

suspend fun GroupCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  filter: suspend GroupCommandSender.(TencentGroupMessageEvent) -> Boolean = { true },
): TencentGroupMessageEvent {
  val mapper = createMapper<GroupCommandSender, TencentGroupMessageEvent>(filter)
  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentGroupMessageEvent, TencentGroupMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentGroupMessageEvent, TencentGroupMessageEvent>(mapper)
    }
  }).also {
    setSourceId(it.message.sourceId)
  }
}

suspend fun FriendUserCommandSender.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  filter: suspend FriendUserCommandSender.(TencentFriendMessageEvent) -> Boolean = { true },
): TencentFriendMessageEvent {
  val mapper = createMapper<FriendUserCommandSender, TencentFriendMessageEvent>(filter)
  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentFriendMessageEvent, TencentFriendMessageEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentFriendMessageEvent, TencentFriendMessageEvent>(mapper)
    }
  }).also {
    setSourceId(it.message.sourceId)
  }
}

suspend inline fun <reified C : UserCommandSender> C.nextButtonInteraction(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend C.(TencentCallbackButtonFilter) -> Boolean = { true },
): TencentCallbackButtonEvent {
  val mapper: suspend (TencentCallbackButtonEvent) -> TencentCallbackButtonEvent? = mapper@{ ev ->
    if (!(this.user.id == ev.user.id && this.subject.id == ev.contact.id)) return@mapper null
    if (!filter(this, TencentCallbackButtonFilter(ev.buttonId, ev.buttonData))) return@mapper null
    ev
  }
  return (if (timeoutMillis == -1L) {
    GlobalEventChannel.syncFromEvent<TencentCallbackButtonEvent, TencentCallbackButtonEvent>(mapper)
  } else {
    withTimeout(timeoutMillis) {
      GlobalEventChannel.syncFromEvent<TencentCallbackButtonEvent, TencentCallbackButtonEvent>(mapper)
    }
  })
}

@Suppress("UNCHECKED_CAST")
suspend inline fun <reified C : UserCommandSender> C.nextMessage(
  timeoutMillis: Long = -1,
  intercept: Boolean = false,
  noinline filter: suspend C.(TencentMessageEvent) -> Boolean = { true },
): TencentMessageEvent {
  return when (this) {

    is FriendUserCommandSender -> {
      nextMessage(
        timeoutMillis,
        intercept,
        filter as (suspend FriendUserCommandSender.(TencentFriendMessageEvent) -> Boolean)
      )
    }

    is GroupCommandSender -> {
      nextMessage(
        timeoutMillis,
        intercept,
        filter as (suspend GroupCommandSender.(TencentGroupMessageEvent) -> Boolean)
      )
    }

    is GuildUserCommandSender -> {
      nextMessage(
        timeoutMillis,
        intercept,
        filter as (suspend GuildUserCommandSender.(TencentGuildPrivateMessageEvent) -> Boolean)
      )
    }

    is GuildChannelCommandSender -> {
      nextMessage(
        timeoutMillis,
        intercept,
        filter as (suspend GuildChannelCommandSender.(TencentGuildMessageEvent) -> Boolean)
      )
    }

    else -> TODO()
  }
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
