package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryWithIsolation
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.webui.endpoints.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import java.lang.reflect.InvocationTargetException
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
  // 同一 plugin key 在子 route 上 install 会覆盖父 route 的同 key 配置
  // (Ktor route-scoped plugin "more specific install wins" 语义). 因此非根 path
  // 的 effective group 必须显式包含根 path 的 common interceptor (如 accessLogging),
  // 否则 /contact 这类子 path 会绕过 access logging / 权限链.
  // 根 common interceptor (如 accessLogging) 总是前置于子路径拦截器执行,
  // priority 仅在各 path 自身的拦截器内排序. 这是刻意设计: 鉴权/用户注入必须先于业务拦截器.
  val rootInterceptors = commonCallInterceptors[""].orEmpty()
  val effectiveCommonCallInterceptors =
    if (rootInterceptors.isEmpty()) commonCallInterceptors
    else commonCallInterceptors.mapValues { (path, group) ->
      if (path.isEmpty()) group else rootInterceptors + group
    }
  routing {
    effectiveCommonCallInterceptors.forEach { (path, group) ->
      if (group.isEmpty()) return@forEach
      route(path) {
        install(AronaCommonRouteInterceptors) {
          interceptors = group
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
                  install(AronaAdminRouteInterceptors) {
                    interceptors = adminCallInterceptors
                  }
                }
                // Ktor 3 的 handle lambda 是 `suspend RoutingContext.() -> Unit`, 没有 Unit 入参; endpoint 实例
                // 来自外层显式命名的 forEach { endpoint -> }, 避免依赖隐式 `it` 跨多层 lambda 解析的脆弱性.
                handle {
                  try {
                    call.checkTransaction(endpoint, method)
                  } catch (_: HaltPipeline) {
                    // no-op: 响应已由 endpoint/拦截器写出, RoutingContext 自然完结.
                  } catch (e: InvocationTargetException) {
                    if (!e.isHaltPipeline()) throw e
                  }
                }
              }
            }
          }
        }
      }
  }
}

/**
 * 反射 interceptor 列表 (一组 endpoint:method 对) 在 ApplicationCallPipeline.Call phase 上的批量执行.
 *
 * 替代 Ktor 3.4 起 deprecated 的 `Route.intercept(phase, block)` 扩展函数. 仍走 pipeline base API
 * (ApplicationCallPipeline.intercept), 保留 PipelineContext.finish() 的短路语义 — 这是当前
 * [HaltPipeline] sentinel exception 模型的硬依赖, RouteScopedPlugin simplified `onCall` 不能等价替代.
 *
 * 注: [AronaBackendRouteInterceptor.phase] 当前**未在运行时使用** (项目无任何非默认 phase 调用方),
 * 所有 interceptor 统一在 Call phase 执行. 该字段保留为 API 兼容占位; 后续若要彻底移除是 API
 * breaking, 单独立项.
 *
 * 拆成 [AronaCommonRouteInterceptors] / [AronaAdminRouteInterceptors] 两个独立 plugin key 是
 * 防御性设计: Ktor route-scoped plugin 同一 key 在嵌套 route 上的 "more specific install wins"
 * 语义会让内层 admin install 覆盖外层 common install, 共用 key 即使当前流程行为正确, 未来路由
 * 重构时也容易踩坑.
 */
private fun InvocationTargetException.isHaltPipeline(): Boolean =
  targetException is HaltPipeline || cause is HaltPipeline

// internal (而非 private) 是为了给 arona-core 模块内的 routing smoke test 暴露 plugin / config 类型;
// 模块外部 (插件 / 下游) 仍不可见, 不构成 API 暴露面扩张.
internal class AronaRouteInterceptorsConfig {
  var interceptors: List<Pair<Any, KFunction<*>>> = emptyList()
}

internal class AronaCommonRouteInterceptors private constructor() {
  companion object Plugin : BaseRouteScopedPlugin<AronaRouteInterceptorsConfig, AronaCommonRouteInterceptors> {
    override val key = AttributeKey<AronaCommonRouteInterceptors>("AronaCommonRouteInterceptors")

    override fun install(
      pipeline: ApplicationCallPipeline,
      configure: AronaRouteInterceptorsConfig.() -> Unit,
    ): AronaCommonRouteInterceptors {
      val config = AronaRouteInterceptorsConfig().apply(configure)
      pipeline.intercept(ApplicationCallPipeline.Call) {
        try {
          config.interceptors.forEach { (endpoint, method) ->
            call.checkTransaction(endpoint, method)
          }
        } catch (_: HaltPipeline) {
          finish()
        } catch (e: InvocationTargetException) {
          if (e.isHaltPipeline()) finish() else throw e
        }
      }
      return AronaCommonRouteInterceptors()
    }
  }
}

internal class AronaAdminRouteInterceptors private constructor() {
  companion object Plugin : BaseRouteScopedPlugin<AronaRouteInterceptorsConfig, AronaAdminRouteInterceptors> {
    override val key = AttributeKey<AronaAdminRouteInterceptors>("AronaAdminRouteInterceptors")

    override fun install(
      pipeline: ApplicationCallPipeline,
      configure: AronaRouteInterceptorsConfig.() -> Unit,
    ): AronaAdminRouteInterceptors {
      val config = AronaRouteInterceptorsConfig().apply(configure)
      pipeline.intercept(ApplicationCallPipeline.Call) {
        try {
          config.interceptors.forEach { (endpoint, method) ->
            call.checkTransaction(endpoint, method)
          }
        } catch (_: HaltPipeline) {
          finish()
        } catch (e: InvocationTargetException) {
          if (e.isHaltPipeline()) finish() else throw e
        }
      }
      return AronaAdminRouteInterceptors()
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

fun withoutTransaction(clazz: KClass<*>, fn: KFunction<*>): Boolean =
  fn.findAnnotation<AronaBackendRouteInterceptor>()?.withoutTransaction == true ||
    clazz.findAnnotation<AronaBackendEndpoint>()?.withoutTransaction == true

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
