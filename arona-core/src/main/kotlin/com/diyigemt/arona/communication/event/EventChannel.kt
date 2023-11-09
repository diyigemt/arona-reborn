package com.diyigemt.arona.communication.event

import kotlinx.coroutines.*
import org.jetbrains.annotations.Contract
import java.util.function.Consumer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass

abstract class EventChannel<out BaseEvent : AbstractEvent>(
  val baseEventClass: KClass<out BaseEvent>,
  val defaultCoroutineContext: CoroutineContext,
) {
  fun filter(filter: suspend (event: BaseEvent) -> Boolean): EventChannel<BaseEvent> {
    return FilterEventChannel(this, filter)
  }

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  @kotlin.internal.LowPriorityInOverloadResolution
  fun filter(filter: (event: BaseEvent) -> Boolean): EventChannel<BaseEvent> {
    return filter { runBIO { filter(it) } }
  }

  inline fun <reified E : AbstractEvent> filterIsInstance(): EventChannel<E> = filterIsInstance(E::class)

  fun <E : AbstractEvent> filterIsInstance(kClass: KClass<out E>): EventChannel<E> {
    return filter { kClass.isInstance(it) }.cast()
  }

  fun <E : AbstractEvent> filterIsInstance(clazz: Class<out E>): EventChannel<E> = filterIsInstance(clazz.kotlin)

  abstract fun context(vararg coroutineContexts: CoroutineContext): EventChannel<BaseEvent>

  /**
   * 创建一个新的 [EventChannel], 该 [EventChannel] 包含 [this.coroutineContext][defaultCoroutineContext] 和添加的 [coroutineExceptionHandler]
   * @see context
   */
  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  @kotlin.internal.LowPriorityInOverloadResolution
  fun exceptionHandler(coroutineExceptionHandler: CoroutineExceptionHandler): EventChannel<BaseEvent> {
    return context(coroutineExceptionHandler)
  }

  /**
   * 创建一个新的 [EventChannel], 该 [EventChannel] 包含 [`this.coroutineContext`][defaultCoroutineContext] 和添加的 [coroutineExceptionHandler]
   * @see context
   */
  fun exceptionHandler(coroutineExceptionHandler: (exception: Throwable) -> Unit): EventChannel<BaseEvent> {
    return context(CoroutineExceptionHandler { _, throwable ->
      coroutineExceptionHandler(throwable)
    })
  }

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  @kotlin.internal.LowPriorityInOverloadResolution
  fun exceptionHandler(coroutineExceptionHandler: Consumer<Throwable>): EventChannel<BaseEvent> {
    return exceptionHandler { coroutineExceptionHandler.accept(it) }
  }

  fun parentScope(coroutineScope: CoroutineScope): EventChannel<BaseEvent> {
    return context(coroutineScope.coroutineContext)
  }

  fun parentJob(job: Job): EventChannel<BaseEvent> {
    return context(job)
  }

  inline fun <reified E : AbstractEvent> subscribe(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend E.(E) -> ListeningStatus,
  ): Listener<E> = subscribe(E::class, coroutineContext, handler)

  fun <E : AbstractEvent> subscribe(
    eventClass: KClass<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend E.(E) -> ListeningStatus,
  ): Listener<E> = subscribeInternal(
    eventClass,
    createListener(coroutineContext) { it.handler(it); }
  )

  inline fun <reified E : AbstractEvent> subscribeAlways(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend E.(E) -> Unit,
  ): Listener<E> = subscribeAlways(E::class, coroutineContext, handler)

  fun <E : AbstractEvent> subscribeAlways(
    eventClass: KClass<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend E.(E) -> Unit,
  ): Listener<E> = subscribeInternal(
    eventClass,
    createListener(coroutineContext) { it.handler(it); ListeningStatus.LISTENING }
  )

  inline fun <reified E : AbstractEvent> subscribeOnce(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    noinline handler: suspend E.(E) -> Unit,
  ): Listener<E> = subscribeOnce(E::class, coroutineContext, handler)

  fun <E : AbstractEvent> subscribeOnce(
    eventClass: KClass<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: suspend E.(E) -> Unit,
  ): Listener<E> = subscribeInternal(
    eventClass,
    createListener(coroutineContext) { it.handler(it); ListeningStatus.STOPPED }
  )

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  @kotlin.internal.LowPriorityInOverloadResolution
  fun <E : AbstractEvent> subscribeAlways(
    eventClass: Class<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: Consumer<E>,
  ): Listener<E> = subscribeInternal(
    eventClass.kotlin,
    createListener(coroutineContext) { event ->
      runInterruptible(Dispatchers.IO) { handler.accept(event) }
      ListeningStatus.LISTENING
    }
  )

  fun <E : AbstractEvent> subscribe(
    eventClass: Class<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: java.util.function.Function<E, ListeningStatus>,
  ): Listener<E> = subscribeInternal(
    eventClass.kotlin,
    createListener(coroutineContext) { event ->
      runInterruptible(Dispatchers.IO) { handler.apply(event) }
    }
  )

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  @kotlin.internal.LowPriorityInOverloadResolution
  fun <E : AbstractEvent> subscribeOnce(
    eventClass: Class<out E>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    handler: Consumer<E>,
  ): Listener<E> = subscribeInternal(
    eventClass.kotlin,
    createListener(coroutineContext) { event ->
      runInterruptible(Dispatchers.IO) { handler.accept(event) }
      ListeningStatus.STOPPED
    }
  )

  protected abstract fun <E : AbstractEvent> registerListener(eventClass: KClass<out E>, listener: Listener<E>)

  internal fun <E : AbstractEvent> registerListener0(eventClass: KClass<out E>, listener: Listener<E>) {
    return registerListener(eventClass, listener)
  }

  private fun <L : Listener<E>, E : AbstractEvent> subscribeInternal(eventClass: KClass<out E>, listener: L): L {
    registerListener(eventClass, listener)
    return listener
  }

  @Contract("_ -> new")
  protected abstract fun <E : AbstractEvent> createListener(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E>

  internal fun <E : AbstractEvent> createListener0(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E> = createListener(coroutineContext, listenerBlock)

}


internal open class FilterEventChannel<BaseEvent : AbstractEvent>(
  private val delegate: EventChannel<BaseEvent>,
  private val filter: suspend (event: BaseEvent) -> Boolean,
) : EventChannel<BaseEvent>(delegate.baseEventClass, delegate.defaultCoroutineContext) {
  private fun <E : AbstractEvent> intercept(block: suspend (E) -> ListeningStatus): suspend (E) -> ListeningStatus {
    return { ev ->
      val filterResult = try {
        @Suppress("UNCHECKED_CAST")
        baseEventClass.isInstance(ev) && filter(ev as BaseEvent)
      } catch (e: Throwable) {
        if (e is ExceptionInEventChannelFilterException) throw e // wrapped by another filter
        throw ExceptionInEventChannelFilterException(ev, this, cause = e)
      }
      if (filterResult) block.invoke(ev)
      else ListeningStatus.LISTENING
    }
  }

  override fun <E : AbstractEvent> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
    delegate.registerListener0(eventClass, listener)
  }

  override fun <E : AbstractEvent> createListener(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E> = delegate.createListener0(coroutineContext, intercept(listenerBlock))

  override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<BaseEvent> {
    return delegate.context(*coroutineContexts)
  }
}

internal sealed class EventChannelImpl<E : AbstractEvent>(
  baseEventClass: KClass<out E>, defaultCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : EventChannel<E>(baseEventClass, defaultCoroutineContext) {
  private val eventListeners = EventListeners()

  suspend fun <E : AbstractEvent> broadcastEventImpl(event: E): E {
    callListeners(event)
    return event
  }

  private suspend fun callListeners(event: AbstractEvent) {
    logEvent(event)
    eventListeners.callListeners(event)
  }

  override fun <E : AbstractEvent> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
    eventListeners.addListener(eventClass, listener)
  }

  override fun <E : AbstractEvent> createListener(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E> {
    val context = this.defaultCoroutineContext + coroutineContext
    return SafeListener(
      parentJob = context[Job],
      subscriberContext = context,
      listenerBlock = listenerBlock
    )
  }

  private fun logEvent(event: AbstractEvent) {
    // TODO
  }

  override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<E> {
    val newDefaultContext = coroutineContexts.fold(defaultCoroutineContext) { acc, coroutineContext ->
      acc + coroutineContext
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    return object : DelegateEventChannel<E>(this) {
      override fun <E : AbstractEvent> createListener(
        coroutineContext: CoroutineContext,
        listenerBlock: suspend (E) -> ListeningStatus,
      ): Listener<E> {
        return super.createListener(
          newDefaultContext + coroutineContext,
          listenerBlock
        )
      }

      override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<E> {
        return delegate.context(newDefaultContext, *coroutineContexts)
      }
    }
  }
}


internal abstract class DelegateEventChannel<BaseEvent : AbstractEvent>(
  protected val delegate: EventChannel<BaseEvent>,
) : EventChannel<BaseEvent>(delegate.baseEventClass, delegate.defaultCoroutineContext) {

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  override fun <E : AbstractEvent> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
    delegate.registerListener0(eventClass, listener)
  }

  @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
  override fun <E : AbstractEvent> createListener(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E> = delegate.createListener0(coroutineContext, listenerBlock)

  override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<BaseEvent> {
    return delegate.context(*coroutineContexts)
  }
}

internal class EventChannelToEventDispatcherAdapter<E : AbstractEvent> private constructor(
  baseEventClass: KClass<out E>, defaultCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : EventChannelImpl<E>(baseEventClass, defaultCoroutineContext) {
  companion object {
    val instance by lazy { EventChannelToEventDispatcherAdapter(AbstractEvent::class, EmptyCoroutineContext) }
  }
}


object GlobalEventChannel : EventChannel<AbstractEvent>(AbstractEvent::class, EmptyCoroutineContext) {
  private val instance = EventChannelToEventDispatcherAdapter.instance

  override fun <E : AbstractEvent> registerListener(eventClass: KClass<out E>, listener: Listener<E>) {
    return instance.registerListener0(eventClass, listener)
  }

  override fun <E : AbstractEvent> createListener(
    coroutineContext: CoroutineContext,
    listenerBlock: suspend (E) -> ListeningStatus,
  ): Listener<E> = instance.createListener0(coroutineContext, listenerBlock)

  override fun context(vararg coroutineContexts: CoroutineContext): EventChannel<AbstractEvent> {
    return instance.context(*coroutineContexts)
  }
}

suspend inline fun <T, R> T.runBIO(
  crossinline block: T.() -> R,
): R = runInterruptible(context = Dispatchers.IO, block = { block() })

@OptIn(ExperimentalContracts::class)
inline fun <reified T> Any?.cast(): T {
  contract { returns() implies (this@cast is T) }
  return this as T
}

class ExceptionInEventChannelFilterException(
  val event: AbstractEvent,
  val eventChannel: EventChannel<*>,
  override val message: String = "Exception in EventHandler",
  override val cause: Throwable,
) : IllegalStateException()
