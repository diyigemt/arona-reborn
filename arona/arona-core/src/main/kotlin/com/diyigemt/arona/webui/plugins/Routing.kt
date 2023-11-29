package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.database.DatabaseProvider.sqlDbQuerySuspended
import com.diyigemt.arona.utils.ReflectionUtil
import com.diyigemt.arona.webui.endpoints.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation

fun Application.configureRouting() {
  val endpoints = ReflectionUtil.scanTypeAnnotatedObjectInstance(AronaBackendEndpoint::class)
  val adminCallInterceptors = endpoints.map {
    ReflectionUtil
      .scanMethodWithAnnotated<AronaBackendAdminRouteInterceptor>(it::class).map { method ->
        it to method
      }
  }.flatten().sortedBy { (_, method) -> method.findAnnotation<AronaBackendAdminRouteInterceptor>()!!.priority }
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
            checkTransaction(father, method)
          }
        }
      }
    }
    endpoints
      .forEach {
        val basePath = it::class.findAnnotation<AronaBackendEndpoint>()!!.path
        val get = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointGet>(it::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointGet>()!!.path to method }
        val post = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointPost>(it::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointPost>()!!.path to method }
        val delete = ReflectionUtil.scanMethodWithAnnotated<AronaBackendEndpointDelete>(it::class)
          .map { method -> method.findAnnotation<AronaBackendEndpointDelete>()!!.path to method }
        val methodList = listOf(HttpMethod.Get to get, HttpMethod.Post to post, HttpMethod.Delete to delete)
        route(basePath) {
          methodList.forEach { (httpMethod, handlers) ->
            handlers.forEach { (path, method) ->
              route(path, httpMethod) {
                if (method.hasAnnotation<AronaBackendAdminEndpoint>() || it::class.hasAnnotation<AronaBackendAdminEndpoint>()) {
                  intercept(ApplicationCallPipeline.Call) {
                    adminCallInterceptors.forEach { (father, method) ->
                      checkTransaction(father, method)
                    }
                  }
                }
                handle { _ ->
                  checkTransaction(it, method)
                }
              }
            }
          }
        }
      }
  }
}

fun withoutTransaction(clazz: KClass<*>, fn: KFunction<*>) = clazz
  .findAnnotation<AronaBackendEndpoint>()?.withoutTransaction
  ?: fn.findAnnotation<AronaBackendRouteInterceptor>()?.withoutTransaction ?: false

suspend fun PipelineContext<Unit, ApplicationCall>.checkTransaction(clazz: Any, fn: KFunction<*>) {
  if (withoutTransaction(clazz::class, fn)) {
    fn.callSuspend(clazz, this)
  } else {
    sqlDbQuerySuspended {
      fn.callSuspend(clazz, this)
    }
  }
}
