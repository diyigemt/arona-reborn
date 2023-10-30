package com.diyigemt.arona.plugins

import com.diyigemt.arona.command.AbstractCommand
import com.diyigemt.arona.command.CommandManager
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

internal val <T> T.job: Job where T : CoroutineScope, T : AronaAbstractPlugin get() = this.coroutineContext[Job]!!

internal val Throwable.rootCauseOrSelf: Throwable get() = generateSequence(this) { it.cause }.lastOrNull() ?: this

internal fun Path.mkdir(): Boolean = this.toFile().mkdir()

object PluginManager {
  private val logger = KtorSimpleLogger("PluginManager")
  private val plugins = mutableListOf<AronaPlugin>()
  private val rootPath: Path = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath()
  private val pluginsFolder by lazy {
    rootPath.resolve("plugins").apply { mkdir() }.toFile()
  }
  val pluginsDataPath: Path = rootPath.resolve("data").apply { mkdir() }
  val pluginsConfigPath: Path = rootPath.resolve("config").apply { mkdir() }
  fun loadPluginFromPluginDirectory() {
    pluginsFolder
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
      // 注册指令
      val pluginClassLoader = pluginInstance::class.java.classLoader
      val reflections = org.reflections.Reflections(
        org.reflections.util.ConfigurationBuilder()
          .forPackage(
            pluginInstance::class.java.packageName,
            pluginClassLoader
          )
      )
      val query = org.reflections.scanners.Scanners.SubTypes
        .of(AbstractCommand::class.java)
        .asClass<AbstractCommand>(pluginClassLoader)
      query.apply(reflections.store).forEach { clazz ->
        clazz as Class<AbstractCommand>
        CommandManager.registerCommand(clazz.kotlin.objectInstance!!, false)
      }
      plugins.add(pluginInstance)
    }
  }
}
