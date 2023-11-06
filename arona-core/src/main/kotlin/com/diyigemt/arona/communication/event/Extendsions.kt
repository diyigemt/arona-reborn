package com.diyigemt.arona.communication.event

import com.diyigemt.arona.communication.command.AbstractUserCommandSender
import com.diyigemt.arona.communication.command.CommandSender
import com.diyigemt.arona.communication.command.CommandSender.Companion.toCommandSender
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.reflect.KClass

suspend inline fun <reified E : TencentEvent, R : Any> EventChannel<*>.syncFromEvent(
  noinline mapper: suspend (E) -> R?
): R = coroutineScope {
  suspendCancellableCoroutine { cont ->
    var listener: Listener<E>? = null
    listener = this@syncFromEvent.parentScope(this).subscribe(E::class) { event ->
      val result = kotlin.runCatching {
        mapper(event) ?: return@subscribe ListeningStatus.LISTENING
      }
      try {
        cont.resumeWith(result)
      } finally {
        listener?.complete() // ensure completed on exceptions
      }
      return@subscribe ListeningStatus.STOPPED
    }
    cont.invokeOnCancellation {
      kotlin.runCatching { listener.cancel("syncFromEvent outer scope cancelled", it) }
    }
  }
}
