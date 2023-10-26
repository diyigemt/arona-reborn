package com.diyigemt.arona.communication.event

import com.diyigemt.arona.utils.userLogger
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal class ListenerRegistry(
  val listener: Listener<AbstractEvent>,
  val type: KClass<out AbstractEvent>,
)

enum class ListeningStatus {
  LISTENING,
  STOPPED
}

interface Listener<in E : AbstractEvent> : CompletableJob {
  suspend fun onEvent(event: E): ListeningStatus?
}

internal class SafeListener<in E : AbstractEvent> internal constructor(
  parentJob: Job?,
  subscriberContext: CoroutineContext,
  private val listenerBlock: suspend (E) -> ListeningStatus
) : Listener<E>, CompletableJob by SupervisorJob(parentJob) {
  private val subscriberContext: CoroutineContext = subscriberContext + this // override Job.
  override fun toString() = "SafeListener(subscriberContext=${subscriberContext.minusKey(Job)})"
  override suspend fun onEvent(event: E): ListeningStatus? = runCatching {
    withContext(subscriberContext) {
      listenerBlock.invoke(event)
    }.also { if (it == ListeningStatus.STOPPED) this.complete() }
  }.onFailure {
    val subscriberExceptionHandler = subscriberContext[CoroutineExceptionHandler]
    if (subscriberExceptionHandler == null) {
      val logger = if (event is TencentEvent) event.bot.logger else userLogger
      val subscriberName = subscriberContext[CoroutineName]?.name ?: "<unnamed>"
      val broadcasterName = currentCoroutineContext()[CoroutineName]?.name ?: "<unnamed>"
      val message =
        "An exception occurred when processing event. " +
            "Subscriber scope: '$subscriberName'. " +
            "Broadcaster scope: '$broadcasterName'"
      logger.error(message)
      logger.error(it)
    } else {
      subscriberExceptionHandler.handleException(subscriberContext, it)
    }
    ListeningStatus.LISTENING
  }.getOrNull()
}

internal class EventListeners {
  private val container = ConcurrentLinkedQueue<ListenerRegistry>()

  fun clear() {
    container.clear()
  }

  internal suspend fun <E : AbstractEvent> callListeners(event: E) {
    for (registry in container) {
      if (!registry.type.isInstance(event)) continue
      supervisorScope {
        launch {
          process(container, registry, registry.listener, event)
        }
      }
    }
  }

  internal fun <E : AbstractEvent> addListener(eventClass: KClass<E>, listener: Listener<E>) {
    @Suppress("UNCHECKED_CAST")
    val node = ListenerRegistry(listener as Listener<AbstractEvent>, eventClass)
    container.add(node)
    listener.invokeOnCompletion {
      container.remove(node)
    }
  }

  private suspend fun <E : AbstractEvent> process(
    container: MutableCollection<ListenerRegistry>,
    registry: ListenerRegistry,
    listener: Listener<AbstractEvent>,
    event: E,
  ) {
    listener as SafeListener
    if (listener.onEvent(event) == ListeningStatus.STOPPED) {
      container.remove(registry)
    }
  }
}
