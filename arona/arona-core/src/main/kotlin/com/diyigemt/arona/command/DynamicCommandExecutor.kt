package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.contact.Contact.Companion.toContactDocumentOrNull
import com.diyigemt.arona.communication.contact.User.Companion.toUserDocumentOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.Permission.Companion.testPermission
import com.diyigemt.arona.utils.currentDate
import com.diyigemt.arona.utils.currentDateTime
import com.diyigemt.arona.utils.currentTime
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 反射重置 Clikt 4.2.1 内部的 `_contextConfig`. fork 时代专门提供了 `context2 { ... }` 扩展做替换式重置;
 * 切回 Maven 后没有等价 API, 但内部字段仍存在. 在每次 [context] 之前调一次, 等价于 fork 的 context2 行为:
 * 让 _contextConfig 不再随调用次数线性叠加闭包, 避免单例 worker 跨次 parse 无限增长内存与构建开销.
 */
private val contextConfigField: java.lang.reflect.Field by lazy {
  CliktCommand::class.java.getDeclaredField("_contextConfig").apply { isAccessible = true }
}
@Suppress("UNCHECKED_CAST")
private fun CliktCommand.resetClicktContext() {
  contextConfigField.set(this, ({} as Context.Builder.() -> Unit))
}

internal sealed class DynamicCommandExecutor(
  val path: List<String>,
  private val primaryName: String,
  protected val parentSignature: CommandSignature,
) : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(primaryName)) {
  val runningCounter = atomic(0)
  private val minimumSignature: List<CommandSignature> = run {
    var ps = parentSignature
    val result = mutableListOf<CommandSignature>()
    val p = path.toMutableList().also {
      it.removeFirst()
    }
    for (it in p) {
      val tmp = ps.children.firstOrNull { c -> c.primaryName == it } ?: break
      result.add(tmp)
      ps = tmp
    }
    result
  }

  protected fun createCommandInstance() = when (this) {
    is DynamicStaticCommandExecutor -> parentSignature.createInstance()
    is DynamicContextualCommandExecutor -> parentSignature.clazz.createObjectOrInstance().also {
      var c = it
      for (child in minimumSignature) {
        val tmp = child.clazz.createObjectOrInstance()
        c.subcommands(tmp)
        c = tmp
      }
    }
  }

  protected suspend fun hasCommandPermission(
    caller: CommandSender,
    args: List<String>,
  ): Boolean {
    val document = caller.subject?.toContactDocumentOrNull()
    val user = caller.user?.toUserDocumentOrNull()
    return if (document != null) {
      val u = document.findContactMemberOrNull(user?.id ?: "") ?: ContactMember(
        "",
        "",
        listOf(DEFAULT_MEMBER_CONTACT_ROLE_ID)
      )
      val environment = mapOf(
        "time" to currentTime().substringBeforeLast(":"),
        "date" to currentDate(),
        "datetime" to currentDateTime(),
        "param1" to (args.getOrNull(0) ?: ""),
        "param2" to (args.getOrNull(1) ?: "")
      )
      return permission.testPermission(u, document.policies, environment)
    } else {
      true
    }
  }

  abstract val runningWorkers: Int
  abstract val idleWorkers: Int
  abstract val pendingTasks: Int
  abstract val permission: Permission

  abstract suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult>
}

internal class DynamicStaticCommandExecutor(
  path: List<String>,
  primaryName: String,
  parentSignature: CommandSignature,
) : DynamicCommandExecutor(
  path,
  primaryName,
  parentSignature,
) {
  override val idleWorkers
    get() = 1 - runningWorkers
  override val pendingTasks
    get() = 0
  override val runningWorkers
    get() = runningCounter.value
  // Static 路径对应 Kotlin object 命令: createCommandInstance() 实际返回同一个 singleton.
  // 因此并发 parse 必须串行 (Mutex), 且每次 parse 前用 resetClicktContext() 把 _contextConfig 清回空块,
  // 避免在 singleton 的生命周期里闭包链无限累积.
  private val worker = run {
    assert(parentSignature.clazz.objectInstance != null)
    createCommandInstance()
  }
  override val permission = worker.permission
  private val lock = Mutex()

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(worker)
      }
    }
    return async {
      runningCounter.incrementAndGet()
      try {
        // Mutex 必须覆盖 "parse + 业务体" 整段, 不能只保护 parse: clikt 把 option/argument 绑定到 this,
        // 业务体(run() 子函数)读这些字段时仍然在用同一份 worker 状态.
        lock.withLock {
          try {
            worker.execute(caller, parentSignature, args)
          } finally {
            worker.resetClicktContext()
          }
        }
      } finally {
        runningCounter.decrementAndGet()
      }
    }
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun AbstractCommand.execute(
  caller: CommandSender,
  signature: CommandSignature,
  args: List<String>,
): CommandExecuteResult {
  return runCatching {
    // 在 context { } 之前先 reset, 等价于 fork 时代的 context2 替换式行为:
    // 单例 worker 多次 parse 之间 _contextConfig 不会累积旧闭包, class 实例不复用时也无副作用.
    resetClicktContext()
    context {
      obj = mutableMapOf(
        "caller" to caller,
        "signature" to signature,
      )
      terminal = commandTerminal
      localization = crsiveLocalization
    }
    parse(args)
    CommandExecuteResult.Success(this)
  }.getOrElse {
    when (it) {
      is MissingArgument -> CommandExecuteResult.UnmatchedSignature(it, this)
      is TimeoutCancellationException -> CommandExecuteResult.Success(this)
      is PrintHelpMessage -> CommandExecuteResult.UnmatchedSignature(it, this)
      else -> CommandExecuteResult.ExecutionFailed(it, this)
    }
  }
}

internal class DynamicContextualCommandExecutor(
  path: List<String>,
  primaryName: String,
  parentSignature: CommandSignature,
) : DynamicCommandExecutor(
  path,
  primaryName,
  parentSignature,
) {
  // Contextual 路径对应 class 命令: createCommandInstance() 每次返回新实例, 天然不存在
  // 跨调用的状态污染. 因此既不需要 Mutex 也不需要 worker pool: 并发上限交给上层 Dispatcher,
  // pendingTasks 永久为 0, idleWorkers 报 Int.MAX_VALUE 表示没有人为容量阻塞.
  override val permission: Permission = createCommandInstance().permission
  override val idleWorkers
    get() = Int.MAX_VALUE
  override val pendingTasks
    get() = 0
  override val runningWorkers
    get() = runningCounter.value

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(createCommandInstance())
      }
    }
    return async {
      runningCounter.incrementAndGet()
      try {
        createCommandInstance().execute(caller, parentSignature, args)
      } finally {
        runningCounter.decrementAndGet()
      }
    }
  }
}
