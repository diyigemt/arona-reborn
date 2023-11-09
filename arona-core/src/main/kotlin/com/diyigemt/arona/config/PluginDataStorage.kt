package com.diyigemt.arona.config

import com.diyigemt.arona.config.internal.MultiFilePluginDataStorageImpl
import java.io.File
import java.nio.file.Path

interface PluginDataStorage {

  fun load(holder: PluginDataHolder, instance: PluginData)

  fun store(holder: PluginDataHolder, instance: PluginData)

}

interface MultiFilePluginDataStorage : PluginDataStorage {

  val directoryPath: Path

  companion object {

    @JvmStatic
    @JvmName("create")
    operator fun invoke(directory: Path): MultiFilePluginDataStorage =
      MultiFilePluginDataStorageImpl(directory)
  }
}

@get:JvmSynthetic
inline val MultiFilePluginDataStorage.directory: File
  get() = this.directoryPath.toFile()
