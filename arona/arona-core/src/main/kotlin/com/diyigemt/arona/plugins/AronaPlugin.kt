package com.diyigemt.arona.plugins

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.communication.event.Event
import com.diyigemt.arona.communication.event.EventChannel
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.config.AutoSavePluginDataHolder
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.utils.SemVersion
import io.github.z4kn4fein.semver.toVersion
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


interface AronaAbstractPlugin : AutoSavePluginDataHolder {
  val logger: Logger
  val description: AronaPluginDescription
}

abstract class AbstractPlugin(
  parentCoroutineContext: EmptyCoroutineContext = EmptyCoroutineContext,
) : AronaAbstractPlugin, CommandOwner, PluginFileExtensions, CoroutineScope {
  final override val logger by lazy {
    KtorSimpleLogger(description.id)
  }
  final override val dataHolderName: String
    get() = this.description.id
  final override val dataFolderPath: Path by lazy {
    PluginManager.pluginsDataPath.resolve(description.id).apply { mkdir() }
  }
  final override val dataFolder: File by lazy {
    dataFolderPath.toFile()
  }
  final override val configFolderPath: Path by lazy {
    PluginManager.pluginsConfigPath.resolve(description.id).apply { mkdir() }
  }
  final override val configFolder: File by lazy {
    configFolderPath.toFile()
  }
  final override val permission: Permission by lazy {
    PermissionService.register(
      PermissionId(this.description.id.lowercase(), "*"),
      "${this.description.id} plugin base permission"
    )
  }
  override val autoSaveIntervalMillis: LongRange = (30 * 1000L)..(10 * 1000L)

  internal val coroutineContextInitializer = {
    CoroutineExceptionHandler { context, throwable ->
      if (throwable.rootCauseOrSelf !is CancellationException) logger.error(
        "Exception in coroutine ${context[CoroutineName]?.name ?: "<unnamed>"} of ${description.name}",
        throwable
      )
    }
      .plus(CoroutineName("Plugin ${description.id}"))
      .plus(
        SupervisorJob(parentCoroutineContext[Job])
      ).also {
        parentCoroutineContext[Job]?.invokeOnCompletion {
          this.cancel()
        }
      }
  }

  fun runSuspend(block: suspend () -> Unit) = launch(coroutineContext) {
    block()
  }

  private fun refreshCoroutineContext(): CoroutineContext {
    return coroutineContextInitializer().also { _coroutineContext = it }.also {
      job.invokeOnCompletion { e ->
        if (e != null) {
          if (e !is CancellationException) logger.error(e)
        }
      }
    }
  }

  private val contextUpdateLock: ReentrantLock =
    ReentrantLock()
  private var _coroutineContext: CoroutineContext? = null
  final override val coroutineContext: CoroutineContext
    get() = _coroutineContext
      ?: contextUpdateLock.withLock { _coroutineContext ?: refreshCoroutineContext() }
  internal fun internalOnEnable() {
    permission
  }
  internal fun internalOnDisable() {
    // TODO
  }
}

abstract class AronaPlugin(
  final override val description: AronaPluginDescription,
) : AbstractPlugin() {
  val version get() = description.version
  abstract fun onLoad()
  fun pluginEventChannel(): EventChannel<Event> = GlobalEventChannel.context(coroutineContext)
}

data class AronaPluginDescription(
  val id: String,
  val name: String = "",
  val author: String = "",
  val version: SemVersion = SemVersion(0, 1, 1),
  val description: String = "",
) {
  constructor(
    id: String,
    name: String = "",
    author: String = "",
    version: String = "0.0,1",
    description: String = "",
  ) : this(id, name, author, version.toVersion(), description)
}

interface PluginFileExtensions {
  val dataFolderPath: Path
  val dataFolder: File
  fun resolveDataFile(relativePath: String): File = dataFolderPath.resolve(relativePath).toFile()
  fun resolveDataPath(relativePath: String): Path = dataFolderPath.resolve(relativePath)
  fun resolveDataFile(relativePath: Path): File = dataFolderPath.resolve(relativePath).toFile()
  fun resolveDataPath(relativePath: Path): Path = dataFolderPath.resolve(relativePath)
  val configFolderPath: Path
  val configFolder: File
  fun resolveConfigFile(relativePath: String): File = configFolderPath.resolve(relativePath).toFile()
  fun resolveConfigPath(relativePath: String): Path = configFolderPath.resolve(relativePath)
  fun resolveConfigFile(relativePath: Path): File = configFolderPath.resolve(relativePath).toFile()
  fun resolveConfigPath(relativePath: Path): Path = configFolderPath.resolve(relativePath)
}
