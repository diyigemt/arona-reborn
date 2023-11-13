package com.diyigemt.arona.config.internal

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.diyigemt.arona.config.MultiFilePluginDataStorage
import com.diyigemt.arona.config.PluginData
import com.diyigemt.arona.config.PluginDataHolder
import com.diyigemt.arona.config.PluginDataStorage
import com.diyigemt.arona.config.internal.serializer.YamlNullableDynamicSerializer
import com.diyigemt.arona.utils.qualifiedNameOrTip
import io.ktor.util.logging.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.io.File
import java.lang.System.currentTimeMillis
import java.nio.file.Path

@Suppress("RedundantVisibilityModifier") // might be public in the future
internal open class MultiFilePluginDataStorageImpl(
  public final override val directoryPath: Path,
  private val logger: Logger = KtorSimpleLogger("MultiFilePluginDataStorageImpl"),
) : PluginDataStorage, MultiFilePluginDataStorage {
  init {
    directoryPath.mkdir()
  }

  public override fun load(holder: PluginDataHolder, instance: PluginData) {
    instance.onInit(holder, this)

    // 0xFEFF is BOM, handle UTF8-BOM
    val file = getPluginDataFile(holder, instance)
    val text = file.readText().removePrefix("\uFEFF")
    if (text.isNotBlank()) {
      val yaml = createYaml(instance)
      try {
        yaml.decodeFromString(instance.updaterSerializer, text)
      } catch (cause: Throwable) {
        file.copyTo(file.resolveSibling("${file.name}.${currentTimeMillis()}.bak"))
        throw cause
      }
    } else {
      this.store(holder, instance) // save an initial copy
    }
  }

  internal fun getPluginDataFileInternal(holder: PluginDataHolder, instance: PluginData): File {
    return getPluginDataFile(holder, instance)
  }

  protected open fun getPluginDataFile(holder: PluginDataHolder, instance: PluginData): File {
    val name = instance.saveName

    val dir = directoryPath.resolve(holder.dataHolderName)
    if (dir.isFile) {
      error("Target directory $dir for holder $holder is occupied by a file therefore data ${instance::class.qualifiedNameOrTip} can't be saved.")
    }
    dir.mkdir()

    val file = dir.resolve("$name.yml")
    if (file.isDirectory) {
      error("Target File $file is occupied by a directory therefore data ${instance::class.qualifiedNameOrTip} can't be saved.")
    }
    return file.toFile().also {
      it.parentFile?.mkdirs()
      it.createNewFile()
    }
  }

  public override fun store(holder: PluginDataHolder, instance: PluginData) {
    getPluginDataFile(holder, instance).writeText(
      kotlin.runCatching {
        val yaml = createYaml(instance)
        yaml.encodeToString(instance.updaterSerializer, Unit).also {
          yaml.decodeFromString(YamlNullableDynamicSerializer, it)
        }
      }.recoverCatching {
        logger.warn(
          "Could not save ${instance.saveName} in YAML format due to exception in YAML encoder. " +
              it
        )
        @Suppress("JSON_FORMAT_REDUNDANT")
        Json {
          serializersModule = instance.serializersModule
          prettyPrint = true
          ignoreUnknownKeys = true
          isLenient = true
          allowStructuredMapKeys = true
          encodeDefaults = true
        }.encodeToString(instance.updaterSerializer, Unit)
      }.getOrElse {
        throw IllegalStateException("Exception while saving $instance, saveName=${instance.saveName}", it)
      }
    )
  }

  private fun createYaml(instance: PluginData): Yaml {
    return Yaml(instance.serializersModule, YamlConfiguration(strictMode = false))
  }
}

internal fun Path.mkdir(): Boolean = this.toFile().mkdir()
internal val Path.isFile: Boolean get() = this.toFile().isFile
internal val Path.isDirectory: Boolean get() = this.toFile().isDirectory
