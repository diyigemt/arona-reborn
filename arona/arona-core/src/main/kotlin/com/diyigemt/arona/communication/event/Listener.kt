@file:OptIn(InternalForInheritanceCoroutinesApi::class)

package com.diyigemt.arona.communication.event

import com.diyigemt.arona.utils.userLogger
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal class ListenerRegistry(
  val listener: Listener<Event>,
  val type: KClass<out Event>,
)

enum class ListeningStatus {
  LISTENING,
  STOPPED
}

// kotlinx.coroutines 把 CompletableJob 标为 stable-for-use / opt-in-for-inheritance
// (@SubclassOptInRequired(InternalForInheritanceCoroutinesApi)); 这里继承是为了让外部
// 直接对 listener 调 complete()/cancel()/invokeOnCompletion(). 实际依赖点:
// - 产线 Extendsions.syncFromEvent: 在 finally 调 listener.complete() 保证异常路径下 STOPPED
// - 测试 helper (CallMessageOpenApiEventSymmetryTest 等 5 处) 直接 listener.complete() 主动停
// 文件级 @OptIn(InternalForInheritanceCoroutinesApi) 显式承担继承不稳定 API 的风险,
// 当前 coroutines 1.10.2 稳定, SafeListener 内部走 SupervisorJob by-delegate 自动跟随
// 上游新增成员; 如未来 coroutines 把该约束升 ERROR, 再切 composition (持有 lifecycle 字段)
// 重构, 同时把 syncFromEvent + 5 处测试 listener.complete() 调用迁到等价 helper.
interface Listener<in E : Event> : CompletableJob {
  suspend fun onEvent(event: E): ListeningStatus?
}

internal class SafeListener<in E : Event> internal constructor(
  parentJob: Job?,
  subscriberContext: CoroutineContext,
  private val listenerBlock: suspend (E) -> ListeningStatus,
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
      val logger = if (event is TencentBotEvent) event.bot.logger else userLogger
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

  internal suspend fun <E : Event> callListeners(event: E) {
    // 先 snapshot 本次广播要触达的监听器, 避免 ConcurrentLinkedQueue 弱一致迭代与并发调度叠加后
    // 导致同一次广播对新增/移除 listener 的可见性漂移.
    val matched = container.filter { it.type.isInstance(event) }
    if (matched.isEmpty()) return
    if (event is SerializedEvent) {
      // 顺序敏感或依赖监听器对事件对象回写的事件, 维持注册顺序串行执行.
      for (registry in matched) {
        process(container, registry, registry.listener, event)
      }
    } else {
      // 普通事件: 所有监听器并发 launch, supervisorScope 等所有子协程完成后返回,
      // 单个 listener 异常被 SafeListener 内部的 runCatching 吃掉, 不会传染.
      supervisorScope {
        for (registry in matched) {
          launch { process(container, registry, registry.listener, event) }
        }
      }
    }
  }

  internal fun <E : Event> addListener(eventClass: KClass<E>, listener: Listener<E>) {
    @Suppress("UNCHECKED_CAST")
    val node = ListenerRegistry(listener as Listener<Event>, eventClass)
    container.add(node)
    listener.invokeOnCompletion {
      container.remove(node)
    }
  }

  private suspend fun <E : Event> process(
    container: MutableCollection<ListenerRegistry>,
    registry: ListenerRegistry,
    listener: Listener<Event>,
    event: E,
  ) {
    listener as SafeListener
    if (listener.onEvent(event) == ListeningStatus.STOPPED) {
      container.remove(registry)
    }
  }
}
