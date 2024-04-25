package com.diyigemt.arona.command

import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.contact.Contact.Companion.toContactDocumentOrNull
import com.diyigemt.arona.communication.contact.User.Companion.toUserDocumentOrNull
import com.diyigemt.arona.database.permission.ContactMember
import com.diyigemt.arona.database.permission.ContactRole.Companion.DEFAULT_MEMBER_CONTACT_ROLE_ID
import com.diyigemt.arona.permission.Permission.Companion.testPermission
import com.diyigemt.arona.utils.commandLineLogger
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
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.reflect.full.createInstance

internal class DynamicCommandExecutor(
  val path: List<String>,
  private val primaryName: String,
  private val parentSignature: CommandSignature,
  var capacity: Int = 8,
  private val workerIdleTimeout: Int = 120, // 扩容120s后若低于负载, 恢复初始容量
) : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(primaryName)) {
  private val rawCapacity = capacity
  private var decrementJob: Job? = null
  private var capacityIncrementCounter = atomic(0)
  val runningCounter = atomic(0)
  private val minimumSignature: List<CommandSignature> = run {
    var ps = parentSignature
    val result = mutableListOf<CommandSignature>()
    for (it in path) {
      val tmp = ps.children.firstOrNull { c -> c.primaryName == it } ?: break
      result.add(tmp)
      ps = tmp
    }
    result
  }
  private val capacityModifyLock = Mutex()
  private val poolModifyLock = Mutex()
  private val pool = ArrayDeque<AbstractCommand>(16)
  val idleWorkers
    get() = pool.size

  private fun createCommandInstance() = parentSignature.clazz.createInstance().also {
    var c = it
    for (child in minimumSignature) {
      val tmp = child.clazz.createInstance()
      c.subcommands(tmp)
      c = tmp
    }
  }

  private fun shouldIncreaseCapacity() {
    if (capacityModifyLock.isLocked) {
      return
    }
    // 快速增长临时扩容
    if (runningCounter.value >= 2 * capacity) {
      if (!capacityModifyLock.tryLock()) {
        return
      }
      decrementJob?.cancel()
      capacity *= 2
      capacityIncrementCounter.incrementAndGet()
      decrementJob = launch {
        while (true) {
          delay(workerIdleTimeout * 1000L)
          if (idleWorkers >= capacity / 2) {
            capacityModifyLock.withLock {
              capacity = max(rawCapacity, capacity / 2)
            }
            commandLineLogger.debug("trigger decrement, target=$primaryName, capacity=$capacity")
            if (capacityIncrementCounter.decrementAndGet() <= 0) {
              break
            }
          }
        }
      }
      capacityModifyLock.unlock()
      commandLineLogger.debug("trigger increment, target=$primaryName, capacity=$capacity")
    }
    commandLineLogger.debug("pop, target=$primaryName, current=${runningCounter.value}")
  }

  private fun shouldDecreaseCapacity() {
    runningCounter.decrementAndGet()
    commandLineLogger.debug("push, target=$primaryName, current=${runningCounter.value}")
  }

  suspend fun execute(args: List<String>, caller: CommandSender, checkPermission: Boolean):
    CommandExecuteResult {
    var worker = poolModifyLock.withLock {
      pool.removeFirstOrNull()
    }
    if (worker == null) {
      worker = createCommandInstance()
    }
    runningCounter.incrementAndGet()
    shouldIncreaseCapacity()
    if (checkPermission) {
      val document = caller.subject?.toContactDocumentOrNull()
      val user = caller.user?.toUserDocumentOrNull()
      if (document != null) {
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
        if (!worker.permission.testPermission(u, document.policies, environment)) {
          commitWorker(worker)
          return CommandExecuteResult.PermissionDenied(worker)
        }
      }
    }
    return runCatching {
      worker.context2 {
        obj = mutableMapOf(
          "caller" to caller,
          "signature" to parentSignature
        )
        terminal = commandTerminal
        localization = crsiveLocalization
      }
      worker.parse(args)
      commitWorker(worker)
      CommandExecuteResult.Success(worker)
    }.getOrElse {
      commitWorker(worker)
      when (it) {
        is MissingArgument -> CommandExecuteResult.UnmatchedSignature(it, worker)
        is TimeoutCancellationException -> CommandExecuteResult.Success(worker)
        is PrintHelpMessage -> CommandExecuteResult.UnmatchedSignature(it, worker)
        else -> CommandExecuteResult.ExecutionFailed(it, worker)
      }
    }
  }

  private suspend fun commitWorker(command: AbstractCommand) {
    // 清除多余的引用
    command.context2 { }
    shouldDecreaseCapacity()
    if (pool.size < capacity) {
      poolModifyLock.withLock {
        pool.addLast(command)
      }
    }
  }
}
