package com.diyigemt.arona.plugins

import com.diyigemt.arona.command.*
import com.diyigemt.arona.command.CommandSignature
import com.diyigemt.arona.config.AutoSavePluginData
import com.diyigemt.arona.config.internal.MultiFilePluginDataStorageImpl
import com.diyigemt.arona.console.CommandLineSubCommand
import com.diyigemt.arona.console.CommandMain
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.utils.commandLineLogger
import com.diyigemt.arona.webui.endpoints.AronaBackendEndpoint
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfig
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder
import com.diyigemt.arona.webui.plugins.RoutingManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.ktor.server.application.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializerOrNull
import org.reflections.Reflections
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.system.exitProcess

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
  private val classLoader = URLClassLoader(
    pluginsFolder
      .listFiles { file -> !file.isDirectory && file.extension == "jar" }
      ?.map { it.toURI().toURL() }?.toTypedArray() ?: arrayOf(),
    Application::class.java.classLoader
  )
  val pluginsDataPath: Path = rootPath.resolve("data").apply { mkdir() }
  val pluginsConfigPath: Path = rootPath.resolve("config").apply { mkdir() }
  fun loadPluginFromPluginDirectory() {
    pluginsFolder
      .listFiles { file -> !file.isDirectory && file.extension == "jar" }
      ?.forEach { loadPluginFromFile(it) }
  }

  fun initPlugin() {
    plugins.forEach {
      logger.info("loading plugin: ${it.description.name}:${it.description.version}")
      it.onLoad()
      logger.info("plugin: ${it.description.name}:${it.description.version} loaded")
    }
  }

  @OptIn(InternalSerializationApi::class)
  @Suppress("UNCHECKED_CAST")
  private fun loadPluginFromFile(jarFile: File) {
    val jar = JarFile(jarFile)

    val manifest = jar.manifest
    val mainClass = manifest.mainAttributes.getValue("Main-Class")
    val pluginClass = classLoader.loadClass(mainClass)

    if (AronaPlugin::class.java.isAssignableFrom(pluginClass)) {
      // 加载runtime反射
      val pluginInstance = pluginClass.kotlin.objectInstance as AronaPlugin
      val pluginClassLoader = pluginInstance::class.java.classLoader
      val reflections = Reflections(
        org.reflections.util.ConfigurationBuilder()
          .forPackage(
            pluginInstance::class.java.packageName,
            pluginClassLoader
          )
      )

      // 注册指令
      loadCommands(reflections, pluginInstance)

      // 注册自动保存的插件配置文件
      val pluginDataQuery = org.reflections.scanners.Scanners.SubTypes
        .of(AutoSavePluginData::class.java)
        .asClass<AutoSavePluginData>(pluginClassLoader)
      pluginDataQuery.apply(reflections.store).forEach { clazz ->
        clazz as Class<AutoSavePluginData>
        val storage = MultiFilePluginDataStorageImpl(pluginsConfigPath)
        val dataInstance = clazz.kotlin.objectInstance
        if (dataInstance == null) {
          commandLineLogger.warn("load plugin data error: ${clazz.name}")
          exitProcess(-1)
        }
        storage.load(pluginInstance, dataInstance)
      }
      // 注册控制台指令
      val consoleCommandQuery = org.reflections.scanners.Scanners.SubTypes
        .of(CommandLineSubCommand::class.java)
        .asClass<CommandLineSubCommand>(pluginClassLoader)
      consoleCommandQuery.apply(reflections.store)
        .filter { CliktCommand::class.java.isAssignableFrom(it) }
        .map { it.getDeclaredConstructor().newInstance() }
        .also {
          CommandMain.registerCommands(it as List<CliktCommand>)
        }
      // 注册webui配置序列化类
      val webuiConfigQuery = org.reflections.scanners.Scanners.SubTypes
        .of(PluginWebuiConfig::class.java)
        .asClass<PluginWebuiConfig>(pluginClassLoader)
      webuiConfigQuery.apply(reflections.store)
        .filter { PluginWebuiConfig::class.java.isAssignableFrom(it) }
        .mapNotNull { it.kotlin.serializerOrNull() }
        .forEach {
          PluginWebuiConfigRecorder.register(pluginInstance, it)
        }
      // 注册webui节点
      val webuiEndpointsQuery = org.reflections.scanners.Scanners.TypesAnnotated
        .of(AronaBackendEndpoint::class.java)
        .asClass<Class<*>>(pluginClassLoader)
      webuiEndpointsQuery.apply(reflections.store)
        .mapNotNull { it.kotlin.objectInstance }
        .forEach {
          RoutingManager.registerEndpoint(it)
        }

      pluginInstance.internalOnEnable()
      plugins.add(pluginInstance)
      // 给插件注册command指令
      PermissionService.register(
        pluginInstance.permissionId("command.*"),
        "插件指令父级权限"
      )
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun loadCommands(reflections: Reflections, instance: AronaPlugin) {
    val cl = instance::class.java.classLoader
    val commandQuery = org.reflections.scanners.Scanners.SubTypes
      .of(AbstractCommand::class.java)
      .asClass<AbstractCommand>(cl)
    // 找到该插件的所有指令
    val commandStorage = commandQuery.apply(reflections.store).map { clazz ->
      clazz as Class<AbstractCommand>
    }
    commandStorage.filter {
      !it.kotlin.hasAnnotation<SubCommand>()
    }.forEach {
      CommandManager.registerCommandSignature(it.kotlin, false)
    }
    val registeredMainCommands = CommandManager.internalGetRegisteredCommands(instance)
    val subCommands = commandStorage.filter {
      it.kotlin.hasAnnotation<SubCommand>() && it.kotlin.findAnnotation<SubCommand>()!!.forClass != AbstractCommand::class
    }.toMutableList()

    fun findParent(signature: CommandSignature, t: KClass<*>): CommandSignature? {
      if (signature.clazz == t) {
        return signature
      }
      return signature.children.firstNotNullOfOrNull { findParent(it, t) }
    }

    var deepCount = 0
    while (deepCount++ < 10 && subCommands.isNotEmpty()) {
      val itr = subCommands.iterator()
      while (itr.hasNext()) {
        val command = itr.next()
        val subCommand = command.kotlin.findAnnotation<SubCommand>()!!
        registeredMainCommands.firstNotNullOfOrNull { findParent(it, subCommand.forClass) }
          ?.also { parent ->
            command.kotlin.createSignature().also { sign ->
              parent.children.add(sign)
              parent.childrenMap[sign.clazz] = sign.targetExtensionFunction
            }
            itr.remove()
          }
      }
    }
    // 整理一下顶级signature的map
    fun mergeChild(root: CommandSignature, self: CommandSignature) {
      root.childrenMap[self.clazz] = self.targetExtensionFunction
      self.children.forEach { child ->
        mergeChild(root, child)
      }
    }

    registeredMainCommands.forEach {
      mergeChild(it, it)
    }

    if (subCommands.isNotEmpty()) {
      logger.warn("some sub command register failed after 10 tries")
      logger.warn("remaining: ${subCommands.joinToString(",") { it.name }}");
    }
  }
}
