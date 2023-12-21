package com.diyigemt.arona.webui.endpoints

import io.ktor.server.application.*
import io.ktor.util.pipeline.*

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpoint(
  val path: String,
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AronaBackendAdminEndpoint

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointGet(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointPost(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointPut(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointDelete(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendRouteInterceptor(
  val priority: RouteInterceptorPriority = RouteInterceptorPriority.NORMAL,
  val phase: ApplicationPhase = ApplicationPhase.Call,
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendAdminRouteInterceptor(
  val priority: RouteInterceptorPriority = RouteInterceptorPriority.NORMAL,
)

enum class ApplicationPhase(val phase: PipelinePhase) {
  Setup(ApplicationCallPipeline.Setup),
  Monitoring(ApplicationCallPipeline.Monitoring),
  Plugins(ApplicationCallPipeline.Plugins),
  Call(ApplicationCallPipeline.Call),
  Fallback(ApplicationCallPipeline.Fallback)
}

enum class RouteInterceptorPriority {
  HIGHEST, HIGH, NORMAL, LOW, LOWEST, MONITOR
}
