package com.diyigemt.arona.plugins

import com.diyigemt.arona.utils.SemVersion
import io.github.z4kn4fein.semver.toVersion
import io.ktor.util.logging.*
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

abstract class AronaPlugin(private val description: AronaPluginDescription) {
  val version
    get() = description.version
  protected val logger = KtorSimpleLogger(description.id)
  abstract fun onLoad()
}

data class AronaPluginDescription(
  val id: String,
  val author: String = "",
  val version: SemVersion = SemVersion(0, 1, 1),
  val description: String = ""
) {
  constructor(
    id: String,
    author: String = "",
    version: String = "0.0,1",
    description: String = ""
  ) : this(id, author, version.toVersion(), description)
}

object PluginManager {
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
    plugins.forEach { it.onLoad() }
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
