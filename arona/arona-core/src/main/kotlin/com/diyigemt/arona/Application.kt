package com.diyigemt.arona

import com.diyigemt.arona.command.BuiltInCommands
import com.diyigemt.arona.command.initExecutorMap
import com.diyigemt.arona.communication.TencentBotClient
import com.diyigemt.arona.communication.image.ImageUploadCache
import com.diyigemt.arona.console.launchConsole
import com.diyigemt.arona.database.DatabaseProvider
import com.diyigemt.arona.database.migration.PluginConfigLeafMigrator
import com.diyigemt.arona.plugins.PluginManager
import com.diyigemt.arona.utils.aronaConfig
import com.diyigemt.arona.utils.closeAronaPools
import com.diyigemt.arona.utils.runSuspend
import com.diyigemt.arona.webui.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

object AronaApplication : CoroutineScope {
  // SupervisorJob 让单个子任务失败不会拉垮整个 app; ApplicationStopping 时统一 cancel.
  override val coroutineContext: CoroutineContext =
    SupervisorJob() + Dispatchers.Default + CoroutineName("AronaApp")

  fun run() {
    System.getProperty("java.awt.headless", "true")
    BuiltInCommands.registerAll()
    BuiltInCommands.registerListeners()
    runSuspend {
      launchConsole()
    }
    // 异步发起: 大集合 background 建索引可能耗时; 启动期间首批 Mongo 请求可能落在未建好的索引上,
    // 走全表扫但结果正确. 如需"启动即具备索引"语义, 改用 runBlocking 阻塞启动.
    runSuspend {
      DatabaseProvider.ensureMongoIndexes()
    }
    // 图片上传凭证缓存的过期清理: 启动即清一次, 之后定时跑; 随 app scope 取消而结束.
    ImageUploadCache.launchCleanup(this)
    // 阻塞: 插件 config 叶子原生 BSON 化迁移, 必须在 PluginManager 初始化 / 任何 User|Contact 读之前
    // 跑完. 旧 BsonString 叶子在批 2 切完 codec 后无法 decode, 这里没跑就直接起服务会全面崩溃.
    // 失败 throw 出来终止 JVM, 让运维介入. 详见 PluginConfigLeafMigrator KDoc.
    runBlocking {
      PluginConfigLeafMigrator.runOnceIfNeeded(DatabaseProvider.defaultMongoDatabase)
    }
    PluginManager.loadPluginFromPluginDirectory()
    PluginManager.initPlugin()
    initExecutorMap()
    TencentBotClient.invoke(aronaConfig.bot).auth()
    // Ktor 3 拆解: 引擎参数 (host/port) 进 configure; rootPath / module 注册进 serverConfig.
    // ApplicationStopping 订阅放到 module 里, 拿到 Application 实例后再走 monitor.subscribe.
    embeddedServer(
      Netty,
      configure = {
        connector {
          port = aronaConfig.web.port
          host = "0.0.0.0"
        }
      },
      rootConfig = serverConfig {
        rootPath = "/api/v1"
        module {
          monitor.subscribe(ApplicationStopping) {
            this@AronaApplication.coroutineContext.cancel()
            closeAronaPools()
          }
          module()
        }
      },
    ).start(wait = true)
  }
}

fun main() {
  AronaApplication.run()
}

fun Application.module() {
  configureHTTP()
  configureRouting()
  configureSerialize()
  configureDoubleReceive()
  configureErrorHandler()
}
