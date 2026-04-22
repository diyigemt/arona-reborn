package com.diyigemt.arona.webui.endpoints

import io.ktor.server.application.*
import io.ktor.util.pipeline.*
import java.sql.Connection

/**
 * SQL 事务隔离级别. 默认采用 [READ_COMMITTED], 与 MariaDB 默认一致, 避免 [READ_UNCOMMITTED] 脏读.
 * endpoint/拦截器可按需通过注解参数覆盖.
 */
enum class TxLevel(val jdbcLevel: Int) {
  READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE),
}

/**
 * 标记 endpoint 路径. 默认会由 Routing 在调用前开一个 Exposed SQL 事务 (isolation = [isolationLevel]).
 *
 * **事务边界仅覆盖 SQL**: Mongo / Redis 调用发生在该事务之外, 跨库一致性由调用方自行处理
 * (例如 [com.diyigemt.arona.database.service.ContactService.createContactAndUser] 的 saga 回滚).
 *
 * 完全不碰 SQL 的 endpoint 可以设置 [withoutTransaction] = true 以省掉启动事务的开销;
 * 但要注意间接调用 (例如通过 `userDocument()` 触发 [com.diyigemt.arona.database.permission.UserSchema] 查询) 也会走 SQL,
 * 关闭事务前需确认整条调用链都不碰 SQL.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpoint(
  val path: String,
  val withoutTransaction: Boolean = false,
  val isolationLevel: TxLevel = TxLevel.READ_COMMITTED,
)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AronaBackendAdminEndpoint

/** 参见 [AronaBackendEndpoint] 的事务说明: 默认开 SQL 事务, Mongo/Redis 不在其中. */
@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointGet(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

/** 参见 [AronaBackendEndpoint] 的事务说明: 默认开 SQL 事务, Mongo/Redis 不在其中. */
@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointPost(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

/** 参见 [AronaBackendEndpoint] 的事务说明: 默认开 SQL 事务, Mongo/Redis 不在其中. */
@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointPut(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

/** 参见 [AronaBackendEndpoint] 的事务说明: 默认开 SQL 事务, Mongo/Redis 不在其中. */
@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendEndpointDelete(
  val path: String = "",
  val withoutTransaction: Boolean = false
)

@Target(AnnotationTarget.FUNCTION)
annotation class AronaBackendRouteInterceptor(
  val priority: RouteInterceptorPriority = RouteInterceptorPriority.NORMAL,
  val phase: ApplicationPhase = ApplicationPhase.Call,
  val withoutTransaction: Boolean = false,
  val isolationLevel: TxLevel = TxLevel.READ_COMMITTED,
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
