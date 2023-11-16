package com.diyigemt.arona.utils

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.loop
import kotlinx.coroutines.*
import java.lang.System.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

public fun CoroutineScope.childScope(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext,
): CoroutineScope =
  CoroutineScope(this.childScopeContext(name, context))

public fun CoroutineContext.childScope(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext,
): CoroutineScope =
  CoroutineScope(this.childScopeContext(name, context))

fun CoroutineScope.childScopeContext(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext,
): CoroutineContext =
  this.coroutineContext.childScopeContext(name, context)

fun CoroutineContext.childScopeContext(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext,
): CoroutineContext =
  this.newCoroutineContextWithSupervisorJob(name) + context.let {
    if (name != null) it + CoroutineName(name)
    else it
  }

inline fun <R> runUnwrapCancellationException(block: () -> R): R {
  try {
    return block()
  } catch (e: CancellationException) {
    // e is like `Exception in thread "main" kotlinx.coroutines.JobCancellationException: Parent job is Cancelling; job=JobImpl{Cancelled}@f252f300`
    // and this is useless.
    throw e.unwrapCancellationException()
    // if (e.suppressed.isNotEmpty()) throw e // preserve details.
    // throw e.findCause { it !is CancellationException } ?: e
  }
}

fun CoroutineContext.newCoroutineContextWithSupervisorJob(name: String? = null): CoroutineContext =
  this + CoroutineName(name ?: "<unnamed>") + SupervisorJob(this[Job])

fun Throwable.unwrapCancellationException(): Throwable = unwrap<CancellationException>()

inline fun <reified E> Throwable.unwrap(): Throwable {
  if (this !is E) return this
  return this.findCause { it !is E }
    ?.also { it.addSuppressed(this) }
    ?: this
}

inline fun Throwable.findCause(maxDepth: Int = 20, filter: (Throwable) -> Boolean): Throwable? {
  var depth = 0
  var rootCause: Throwable? = this
  while (true) {
    if (rootCause?.cause === rootCause) return rootCause
    val current = rootCause?.cause ?: return null
    if (filter(current)) return current
    rootCause = rootCause.cause
    if (depth++ >= maxDepth) return null
  }
}

internal class TimedTask(
  scope: CoroutineScope,
  coroutineContext: CoroutineContext = EmptyCoroutineContext,
  intervalMillis: Long,
  action: suspend CoroutineScope.() -> Unit,
) {
  companion object {
    private const val UNCHANGED = 0L
  }

  private val lastChangedTime = atomic(UNCHANGED)

  fun setChanged() {
    lastChangedTime.value = currentTimeMillis()
  }

  val job: Job = scope.launch(coroutineContext) {
    // `delay` always checks for cancellation
    lastChangedTime.loop { last ->
      val current = currentTimeMillis()
      if (last == UNCHANGED) {
        runIgnoreException<CancellationException> {
          delay(3000) // accuracy not necessary
        } ?: return@launch
      } else {
        if (current - last > intervalMillis) {
          if (!lastChangedTime.compareAndSet(last, UNCHANGED)) return@loop
          action()
        }
        runIgnoreException<CancellationException> {
          delay(3000) // accuracy not necessary
        } ?: return@launch
      }
    }
  }
}


internal fun CoroutineScope.launchTimedTask(
  intervalMillis: Long,
  coroutineContext: CoroutineContext = EmptyCoroutineContext,
  action: suspend CoroutineScope.() -> Unit,
) = TimedTask(this, coroutineContext, intervalMillis, action)

internal inline fun <reified E : Throwable> runIgnoreException(block: () -> Unit): Unit? {
  try {
    return block()
  } catch (e: Throwable) {
    if (e is E) return null
    throw e
  }
}
