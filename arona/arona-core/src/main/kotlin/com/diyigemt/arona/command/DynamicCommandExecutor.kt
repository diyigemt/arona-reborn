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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.reflect.full.createInstance

internal class DynamicCommandExecutor(
  val path: List<String>,
  private val primaryName: String,
  private val parentSignature: CommandSignature,
  var capacity: Int = 10,
) {
  private val rawCapacity = capacity
  val extensionCounter = atomic(0)
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
  private val lock = Mutex()
  private val pool = ArrayDeque<AbstractCommand>()
  val poolSize
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
    // 快速增长临时扩容
    if (extensionCounter.incrementAndGet() - capacity > 1.5 * capacity + 5) {
      capacity = (capacity * 1.5).toInt()
      commandLineLogger.debug("trigger increment, target=$primaryName, capacity=$capacity")
    }
    commandLineLogger.debug("pop, target=$primaryName, current=${extensionCounter.value}")
  }

  private fun shouldDecreaseCapacity() {
    if (extensionCounter.decrementAndGet() < capacity / 1.5 - 5) {
      capacity = max(rawCapacity, (capacity / 1.5).toInt())
      commandLineLogger.debug("trigger decrement, target=$primaryName, capacity=$capacity")
    }
    commandLineLogger.debug("push, target=$primaryName, current=${extensionCounter.value}")
  }

  suspend fun execute(args: List<String>, caller: CommandSender, checkPermission: Boolean):
    CommandExecuteResult {
    var worker = lock.withLock {
      pool.removeFirstOrNull()
    }
    if (worker == null) {
      worker = createCommandInstance()
    }
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
          return CommandExecuteResult.PermissionDenied(worker)
        }
      }
    }
    return runCatching {
      worker.context2 {
        obj = mutableMapOf(
          "worker" to primaryName,
          "root" to worker,
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
      shouldDecreaseCapacity()
      when (it) {
        is MissingArgument -> CommandExecuteResult.UnmatchedSignature(it, worker)
        is TimeoutCancellationException -> CommandExecuteResult.Success(worker)
        is PrintHelpMessage -> CommandExecuteResult.UnmatchedSignature(it, worker)
        else -> CommandExecuteResult.ExecutionFailed(it, worker)
      }
    }
  }

  private suspend fun commitWorker(command: AbstractCommand) {
    shouldDecreaseCapacity()
    lock.withLock {
      if (pool.size < capacity) {
        // 清除多余的引用
        command.context2 { }
        pool.addLast(command)
      }
    }
  }
}
