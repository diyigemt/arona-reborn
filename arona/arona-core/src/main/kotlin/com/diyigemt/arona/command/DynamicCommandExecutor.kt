package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.AbstractCommandSender
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
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  // Static 路径对应 Kotlin object 命令: createCommandInstance() 实际返回同一个 singleton, 无法重建.
  // 因此并发 parse 必须串行 (Mutex). Clikt 5 的 context() 是累积式, 单例长期复用会让 _contextConfig 闭包链
  // 缓慢增长 (每次 parse 加一个 closure); 项目目前命令频次远低于 GC 压力, 接受此微小累积.
  // TODO 长期方案: 等 Clikt 5 暴露官方 reset/replace API 时, 在 Mutex 内部清空闭包链.
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
        // Mutex 必须覆盖 "parse + 业务体" 整段: clikt 把 option/argument 绑定到 this,
        // 业务体读字段时仍在用同一份 worker 状态.
        lock.withLock {
          worker.execute(caller, parentSignature, args)
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
    context {
      // Clikt 5 的 obj 是 Any?, 用类型化容器替代旧 fork 的 mutableMapOf("caller", "signature");
      // AbstractCommand 子类通过 requireObject<DynamicCommandContext>() 一次拿全.
      obj = DynamicCommandContext(caller as AbstractCommandSender, signature)
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
  // Contextual 路径对应 class 命令: createCommandInstance() 每次返回新实例, 天然不存在跨调用污染.
  // 因此既不需要 Mutex 也不需要 worker pool: 并发上限交给上层 Dispatcher;
  // pendingTasks 永久 0, idleWorkers 报 Int.MAX_VALUE 表示没有人为容量阻塞.
  // (旧 fork 时代用 worker pool + context2 重置维持复用; Clikt 5 后 context2 不存在, 用一次性实例代价更小.)
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
