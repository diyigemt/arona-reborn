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
  override val pendingTasks = 0
  override val runningWorkers = runningCounter.value
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
    runningCounter.incrementAndGet()
    return async {
      val result = lock.withLock {
        worker.execute(
          caller,
          parentSignature,
          args,
          suspend = true
        ).also {
          worker.context2 {  }
        }
      }
      runningCounter.decrementAndGet()
      result
    }
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun AbstractCommand.execute(
  caller: CommandSender,
  signature: CommandSignature,
  args: List<String>,
  suspend: Boolean = false
): CommandExecuteResult {
  return runCatching {
    context2 {
      obj = mutableMapOf(
        "caller" to caller,
        "signature" to signature,
        "suspend" to suspend,
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
  private val workerPoolModifyLock = Mutex()
  private val workerPool = ArrayDeque(
    (0 until workerPoolCapacity).map {
      createCommandInstance()
    }
  )
  private val taskPoolModifyLock = Mutex()
  private val taskPool = ArrayDeque<Deferred<CommandExecuteResult>>(workerPoolCapacity)
  override val idleWorkers
    get() = workerPool.size
  override val pendingTasks = 0
  override val runningWorkers = runningCounter.value
  override val permission = workerPool.first().permission

  override suspend fun execute(
    args: List<String>,
    caller: CommandSender,
    checkPermission: Boolean,
  ): Deferred<CommandExecuteResult> {
    if (checkPermission && !hasCommandPermission(caller, args)) {
      return async {
        CommandExecuteResult.PermissionDenied(
          workerPool.firstOrNull() ?: createCommandInstance(),
        )
      }
    }
    val worker = workerPoolModifyLock.withLock {
      workerPool.removeFirstOrNull()
    }
    if (worker == null) {
      val task = async(start = CoroutineStart.LAZY) {
        val w = workerPoolModifyLock.withLock {
          workerPool.removeFirst()
        }
        runningCounter.incrementAndGet()
        val result = w.execute(
          caller,
          parentSignature,
          args
        )
        commitWorker(w)
        result
      }
      taskPoolModifyLock.withLock {
        taskPool.addLast(task)
      }
      return task
    }
    return async {
      runningCounter.incrementAndGet()
      val result = worker.execute(
        caller,
        parentSignature,
        args
      )
      commitWorker(worker)
      result
    }
  }

  private suspend fun commitWorker(command: AbstractCommand) {
    // 清除多余的引用
    command.context2 { }
    runningCounter.decrementAndGet()
    workerPoolModifyLock.withLock {
      if (workerPool.size < workerPoolCapacity) {
        workerPool.addLast(command)
      }
    }
    launch {
      taskPoolModifyLock.withLock {
        taskPool.removeFirstOrNull()?.start()
      }
    }
  }
}
