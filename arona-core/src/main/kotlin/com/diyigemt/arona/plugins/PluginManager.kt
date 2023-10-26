package com.diyigemt.arona.plugins

import com.diyigemt.arona.communication.event.AbstractEvent
import com.diyigemt.arona.communication.event.EventChannel
import com.diyigemt.arona.communication.event.GlobalEventChannel
import com.diyigemt.arona.utils.SemVersion
import io.github.z4kn4fein.semver.toVersion
import io.ktor.util.logging.*
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.*
import org.slf4j.Logger
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.locks.ReentrantLock
import java.util.jar.JarFile
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

interface AronaAbstractPlugin {
  val logger: Logger
  val description: AronaPluginDescription
}

abstract class AbstractPlugin(
  parentCoroutineContext: EmptyCoroutineContext = EmptyCoroutineContext
) : AronaAbstractPlugin, CoroutineScope {
  final override val logger by lazy {
    KtorSimpleLogger(description.id)
  }
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
}

abstract class AronaPlugin(
  final override val description: AronaPluginDescription
) : AbstractPlugin() {
  val version get() = description.version
  abstract fun onLoad()
  fun pluginEventChannel(): EventChannel<AbstractEvent> = GlobalEventChannel.context(coroutineContext)
}
internal val <T> T.job: Job where T : CoroutineScope, T : AronaAbstractPlugin get() = this.coroutineContext[Job]!!

internal val Throwable.rootCauseOrSelf: Throwable get() = generateSequence(this) { it.cause }.lastOrNull() ?: this

data class AronaPluginDescription(
  val id: String,
  val name: String = "",
  val author: String = "",
  val version: SemVersion = SemVersion(0, 1, 1),
  val description: String = ""
) {
  constructor(
    id: String,
    name: String = "",
    author: String = "",
    version: String = "0.0,1",
    description: String = ""
  ) : this(id, name, author, version.toVersion(), description)
}

object PluginManager {
  private val logger = KtorSimpleLogger("PluginManager")
  private val plugins = mutableListOf<AronaPlugin>()
  private val pluginFolder by lazy {
    File("./plugins").apply { mkdir() }
  }
  fun loadPluginFromPluginDirectory() {
    pluginFolder
      .listFiles { file -> !file.isDirectory && file.extension == "jar" }
      ?.forEach { loadPluginFromFile(it) }
  }
  fun initPlugin() {
    plugins.forEach {
      logger.info("loading plugin: ${it.description.name}")
      it.onLoad()
      logger.info("plugin: ${it.description.name} loaded")
    }
  }
  private fun loadPluginFromFile(jarFile: File) {
    val jarURL = jarFile.toURI().toURL()
    val classLoader = URLClassLoader(arrayOf(jarURL))
    val jar = JarFile(jarFile)

    val manifest = jar.manifest
    val mainClass = manifest.mainAttributes.getValue("Main-Class")
    val pluginClass = classLoader.loadClass(mainClass)

    if (AronaPlugin::class.java.isAssignableFrom(pluginClass)) {
      val pluginInstance = pluginClass.kotlin.objectInstance as AronaPlugin
      plugins.add(pluginInstance)
    }
  }
}
