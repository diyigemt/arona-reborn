package com.diyigemt.arona.utils

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException


fun CoroutineScope.childScopeContext(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext
): CoroutineContext =
  this.coroutineContext.childScopeContext(name, context)

fun CoroutineContext.childScopeContext(
  name: String? = null,
  context: CoroutineContext = EmptyCoroutineContext
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

public fun CoroutineContext.newCoroutineContextWithSupervisorJob(name: String? = null): CoroutineContext =
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
