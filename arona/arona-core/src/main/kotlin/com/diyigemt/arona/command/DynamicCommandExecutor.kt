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
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.context2
import com.github.ajalt.clikt.core.subcommands
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
        // singleton worker, clikt 把 option/argument 绑定到 this, 并发 parse 会互相污染.
        // 因此 Mutex 必须覆盖 "parse + 业务体" 整段, 不能只保护 parse.
        lock.withLock {
          try {
            worker.execute(caller, parentSignature, args)
          } finally {
            worker.context2 { }
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
    context2 {
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
  initCapacity: Int = Runtime.getRuntime().availableProcessors() * 2,
) : DynamicCommandExecutor(
  path,
  primaryName,
  parentSignature,
) {
  private val workerPoolCapacity = initCapacity * 2

  // Channel 同时承担"可复用 worker 资源池"和"等待队列"两个角色, 消除之前 ArrayDeque + lazy async
  // 手工排队里 "归还 worker 后先 addLast 再 launch start" 的竞态窗口.
  private val samples = List(workerPoolCapacity) { createCommandInstance() }
  private val workerPool = Channel<AbstractCommand>(workerPoolCapacity).also { pool ->
    samples.forEach { check(pool.trySend(it).isSuccess) { "failed to initialize worker pool" } }
  }
  private val idleCounter = atomic(workerPoolCapacity)
  private val pendingCounter = atomic(0)

  override val idleWorkers
    get() = idleCounter.value
  override val pendingTasks
    get() = pendingCounter.value
  override val runningWorkers
    get() = runningCounter.value
  override val permission = samples.first().permission

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(samples.first())
      }
    }
    return async {
      val worker = acquireWorker()
      idleCounter.decrementAndGet()
      runningCounter.incrementAndGet()
      try {
        worker.execute(caller, parentSignature, args)
      } finally {
        commitWorker(worker)
      }
    }
  }

  private suspend fun acquireWorker(): AbstractCommand {
    pendingCounter.incrementAndGet()
    return try {
      workerPool.receive()
    } finally {
      pendingCounter.decrementAndGet()
    }
  }

  // 非挂起归还: 在 try/finally 里如果用挂起的 send(), 外层被取消时可能再次挂起并被取消, worker 会丢.
  // Channel 容量等于 workerPoolCapacity, 取走多少归还多少, trySend 必然成功.
  private fun commitWorker(command: AbstractCommand) {
    command.context2 { }
    runningCounter.decrementAndGet()
    check(workerPool.trySend(command).isSuccess) { "worker pool overflow while returning worker" }
    idleCounter.incrementAndGet()
  }
}
