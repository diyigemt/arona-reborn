package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryWithIsolation
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.webui.endpoints.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation

object RoutingManager {
  private val endpointObjects: MutableList<Any> = mutableListOf()
  init {
    endpointObjects.addAll(ReflectionUtil.scanTypeAnnotatedObjectInstance(AronaBackendEndpoint::class))
  }
  fun registerEndpoint(endpoint: Any) = endpointObjects.add(endpoint)
  fun endpoints(): List<Any> {
    return endpointObjects
  }
}

fun Application.configureRouting() {
  val endpoints = RoutingManager.endpoints()
  val adminCallInterceptors = endpoints.map {
    ReflectionUtil
      .scanMethodWithAnnotated<AronaBackendAdminRouteInterceptor>(it::class).map { method ->
        it to method
      }
  }.flatten().sortedBy { (_, method) -> method.findAnnotation<AronaBackendAdminRouteInterceptor>()!!.priority }
  // 启动期断言: 存在管理端点却没有任何管理拦截器, 等同于裸奔, 立即失败而不是上线后被发现.
  if (hasAdminEndpoint(endpoints) && adminCallInterceptors.isEmpty()) {
    throw IllegalStateException(
      "No @AronaBackendAdminRouteInterceptor registered while admin endpoints exist; refuse to start."
    )
  }
  val commonCallInterceptors =
    endpoints.map {
      it::class.findAnnotation<AronaBackendEndpoint>()!!.path to ReflectionUtil
        .scanMethodWithAnnotated<AronaBackendRouteInterceptor>(it::class)
        .filter { method ->
          !method.hasAnnotation<AronaBackendAdminRouteInterceptor>()
        }.map { method ->
          it to method
        }
    }.groupBy { it.first }.mapValues { v ->
      v.value.map { it.second }.flatten()
        .sortedBy { (_, method) -> method.findAnnotation<AronaBackendRouteInterceptor>()!!.priority }
    }
  routing {
    commonCallInterceptors.forEach { (path, group) ->
      route(path) {
        group.forEach { (father, method) ->
          val phase = method.findAnnotation<AronaBackendRouteInterceptor>()!!.phase
          intercept(phase.phase) {
            // 拦截器内部已通过响应辅助函数 (errorMessage/unauthorized 等) 写出响应,
            // HaltPipeline 把"跳过后续阶段"的信号回传到 PipelineContext, 调 finish() 阻止 handler 执行.
            try {
              call.checkTransaction(father, method)
            } catch (_: HaltPipeline) {
              finish()
            }
          }
        }
      }
    }
    endpoints
      .forEach { endpoint ->
        val basePath = endpoint::class.findAnnotation<AronaBackendEndpoint>()!!.path
        val get = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointGet>(endpoint::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointGet>()!!.path to method }
        val post = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointPost>(endpoint::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointPost>()!!.path to method }
        val delete = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointDelete>(endpoint::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointDelete>()!!.path to method }
        val put = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointPut>(endpoint::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointPut>()!!.path to method }
        val methodList = listOf(
          HttpMethod.Get to get, HttpMethod.Post to post, HttpMethod.Delete to delete,
          HttpMethod.Put to put
        )
        route(basePath) {
          methodList.forEach { (httpMethod, handlers) ->
            handlers.forEach { (path, method) ->
              route(path, httpMethod) {
                if (method.hasAnnotation<AronaBackendAdminEndpoint>() || endpoint::class.hasAnnotation<AronaBackendAdminEndpoint>()) {
                  intercept(ApplicationCallPipeline.Call) {
                    try {
                      adminCallInterceptors.forEach { (father, method) ->
                        call.checkTransaction(father, method)
                      }
                    } catch (_: HaltPipeline) {
                      finish()
                    }
                  }
                }
                // Ktor 3 的 handle lambda 是 `suspend RoutingContext.() -> Unit`, 没有 Unit 入参; endpoint 实例
                // 来自外层显式命名的 forEach { endpoint -> }, 避免依赖隐式 `it` 跨多层 lambda 解析的脆弱性.
                handle {
                  try {
                    call.checkTransaction(endpoint, method)
                  } catch (_: HaltPipeline) {
                    // no-op: 响应已由 endpoint/拦截器写出, RoutingContext 自然完结.
                  }
                }
              }
            }
          }
        }
      }
  }
}

private val HttpEndpointAnnotations: Set<KClass<out Annotation>> = setOf(
  AronaBackendEndpointGet::class,
  AronaBackendEndpointPost::class,
  AronaBackendEndpointPut::class,
  AronaBackendEndpointDelete::class,
)

private fun hasAdminEndpoint(endpoints: List<Any>): Boolean = endpoints.any { endpoint ->
  if (endpoint::class.hasAnnotation<AronaBackendAdminEndpoint>()) return@any true
  endpoint::class.declaredFunctions.any { fn ->
    val isHttp = fn.annotations.any { ann -> ann.annotationClass in HttpEndpointAnnotations }
    isHttp && fn.hasAnnotation<AronaBackendAdminEndpoint>()
  }
}

fun withoutTransaction(clazz: KClass<*>, fn: KFunction<*>) = clazz
  .findAnnotation<AronaBackendEndpoint>()?.withoutTransaction
  ?: fn.findAnnotation<AronaBackendRouteInterceptor>()?.withoutTransaction ?: false

/**
 * 解析事务隔离级别: 拦截器注解 > 端点注解 > 默认 READ_COMMITTED.
 */
fun resolveIsolationLevel(clazz: KClass<*>, fn: KFunction<*>): TxLevel =
  fn.findAnnotation<AronaBackendRouteInterceptor>()?.isolationLevel
    ?: clazz.findAnnotation<AronaBackendEndpoint>()?.isolationLevel
    ?: TxLevel.READ_COMMITTED

suspend fun ApplicationCall.checkTransaction(clazz: Any, fn: KFunction<*>) {
  if (withoutTransaction(clazz::class, fn)) {
    fn.callSuspend(clazz, this)
  } else {
    sqlDbQueryWithIsolation(resolveIsolationLevel(clazz::class, fn).jdbcLevel) {
      fn.callSuspend(clazz, this@checkTransaction)
    }
  }
}

/**
 * Ktor 2 时代 endpoint/拦截器在 [io.ktor.util.pipeline.PipelineContext] 上调用 `finish()` 跳过后续阶段;
 * Ktor 3 把 routing 主签名换成 [io.ktor.server.routing.RoutingContext], `finish()` 不再可见.
 *
 * 项目把所有反射 endpoint/拦截器统一迁到 [ApplicationCall] receiver, 失去 pipeline 控制权;
 * 因此用这个轻量 sentinel 异常作为"halt 信号": 由 [Route.intercept] 的 PipelineContext 包装层捕获,
 * 在那里再调用 PipelineContext.finish() 真正终止 pipeline. 抛出处不需要再 `return`.
 */
internal class HaltPipeline : RuntimeException() {
  // 业务流控用, 不需要堆栈; 关 stack trace 减开销.
  override fun fillInStackTrace(): Throwable = this
}
