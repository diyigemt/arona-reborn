package com.diyigemt.arona.webui.plugins

import com.diyigemt.arona.webui.endpoints.AronaBackendAdminRouteInterceptor
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.endpoints.AronaBackendRouteInterceptor
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * F2.5-G smoke test: 验证 [AronaCommonRouteInterceptors] / [AronaAdminRouteInterceptors] 在
 * Ktor 3.4.x BaseRouteScopedPlugin + ApplicationCallPipeline.intercept 模型下的 [HaltPipeline]
 * → finish() 短路语义. 不触达 [com.diyigemt.arona.webui.plugins.configureRouting] (依赖 Reflections
 * 全局扫描 / SQL / Redis 等), 直接 install plugin 到 testApplication 内的 minimal route 树.
 *
 * fake interceptor 通过 [AronaBackendEndpoint.withoutTransaction] = true 让 [checkTransaction]
 * 走 callSuspend 直分支, 完全绕开 [com.diyigemt.arona.database.DatabaseProvider.sqlDbQueryWithIsolation].
 */
class AronaRouteInterceptorsTest {

  @AronaBackendEndpoint("/test", withoutTransaction = true)
  private object FakeInterceptors {
    @AronaBackendRouteInterceptor(withoutTransaction = true)
    @Suppress("unused", "RedundantSuspendModifier")
    suspend fun ApplicationCall.commonDeny() {
      respondText("DENY", status = HttpStatusCode.Unauthorized)
      throw HaltPipeline()
    }

    @AronaBackendAdminRouteInterceptor
    @Suppress("unused", "RedundantSuspendModifier")
    suspend fun ApplicationCall.adminDeny() {
      respondText("ADMIN-DENY", status = HttpStatusCode.Forbidden)
      throw HaltPipeline()
    }

    @AronaBackendRouteInterceptor(withoutTransaction = true)
    @Suppress("unused", "RedundantSuspendModifier")
    suspend fun ApplicationCall.commonNoop() {
      // 不抛 HaltPipeline, 让 handler 正常执行
    }
  }

  // 测试 fake 是 nested object, JVM 反射调用需要 isAccessible = true 才能突破嵌套类访问检查;
  // 生产代码扫的全是顶层 public object (LoggerInterceptor 等), 不会踩这个坑.
  private fun method(name: String): KFunction<*> = FakeInterceptors::class
    .declaredMemberExtensionFunctions
    .first { it.name == name }
    .apply { isAccessible = true }

  @Test
  fun `common interceptor halt prevents handler and returns 401`() = testApplication {
    val handlerRan = AtomicBoolean(false)
    application {
      routing {
        route("/protected") {
          install(AronaCommonRouteInterceptors) {
            interceptors = listOf(FakeInterceptors to method("commonDeny"))
          }
          get {
            handlerRan.set(true)
            call.respondText("BODY")
          }
        }
      }
    }

    val response = client.get("/protected")
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals("DENY", response.bodyAsText())
    assertFalse(handlerRan.get(), "handler must not run after HaltPipeline")
  }

  @Test
  fun `admin interceptor halt prevents handler and returns 403`() = testApplication {
    val handlerRan = AtomicBoolean(false)
    application {
      routing {
        route("/admin/something") {
          install(AronaAdminRouteInterceptors) {
            interceptors = listOf(FakeInterceptors to method("adminDeny"))
          }
          get {
            handlerRan.set(true)
            call.respondText("BODY")
          }
        }
      }
    }

    val response = client.get("/admin/something")
    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals("ADMIN-DENY", response.bodyAsText())
    assertFalse(handlerRan.get(), "handler must not run after admin HaltPipeline")
  }

  @Test
  fun `non-halting common interceptor lets handler run normally`() = testApplication {
    val handlerRan = AtomicBoolean(false)
    application {
      routing {
        route("/open") {
          install(AronaCommonRouteInterceptors) {
            interceptors = listOf(FakeInterceptors to method("commonNoop"))
          }
          get {
            handlerRan.set(true)
            call.respondText("BODY")
          }
        }
      }
    }

    val response = client.get("/open")
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals("BODY", response.bodyAsText())
    assertEquals(true, handlerRan.get(), "handler should run when interceptor does not halt")
  }

  /**
   * 防御 configureRouting 合并根路径拦截器后的回归: 子路由自己 install
   * [AronaCommonRouteInterceptors] 时, effective group 应包含父级 deny
   * (模拟 accessLogging prepend 到每个子路径组的效果).
   * 若子 route 的 same-key install 覆盖了父 route, deny 必须仍在 effective group 中.
   */
  @Test
  fun `merged child common install keeps parent common deny`() = testApplication {
    val handlerRan = AtomicBoolean(false)
    application {
      routing {
        route("/parent") {
          install(AronaCommonRouteInterceptors) {
            interceptors = listOf(FakeInterceptors to method("commonDeny"))
          }
          route("/child") {
            // 模拟 effectiveCommonCallInterceptors: 根 deny + 子 noop
            install(AronaCommonRouteInterceptors) {
              interceptors = listOf(
                FakeInterceptors to method("commonDeny"),
                FakeInterceptors to method("commonNoop"),
              )
            }
            get {
              handlerRan.set(true)
              call.respondText("BODY")
            }
          }
        }
      }
    }

    val response = client.get("/parent/child")
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals("DENY", response.bodyAsText())
    assertFalse(handlerRan.get(), "handler must not run after merged parent common HaltPipeline")
  }

  /**
   * 防御 F2.5-G v2 review 修过的 split-key 回归: parent route 的 [AronaCommonRouteInterceptors]
   * install 与 child route 的 [AronaAdminRouteInterceptors] install 必须各自独立, 不能因 same-key
   * "more specific install wins" 让 admin install 吞掉 parent 的 common deny.
   */
  @Test
  fun `child admin install does not override parent common deny`() = testApplication {
    val handlerRan = AtomicBoolean(false)
    application {
      routing {
        route("/parent") {
          install(AronaCommonRouteInterceptors) {
            interceptors = listOf(FakeInterceptors to method("commonDeny"))
          }
          route("/child") {
            install(AronaAdminRouteInterceptors) {
              interceptors = listOf(FakeInterceptors to method("adminDeny"))
            }
            get {
              handlerRan.set(true)
              call.respondText("BODY")
            }
          }
        }
      }
    }

    // 期望: parent commonDeny 先抛 401 短路, admin 不执行, handler 也不执行.
    // 若 split-key 设计被破坏 (例如 admin/common 共用 key), child 上的 admin install 会覆盖父
    // common install, body 会变成 ADMIN-DENY/403 或 BODY/200, 测试立即失败.
    val response = client.get("/parent/child")
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertEquals("DENY", response.bodyAsText())
    assertFalse(handlerRan.get(), "handler must not run after parent common HaltPipeline")
  }
}
