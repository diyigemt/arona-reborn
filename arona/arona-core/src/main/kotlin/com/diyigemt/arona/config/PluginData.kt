package com.diyigemt.arona.config

import com.diyigemt.arona.config.internal.Value
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule

interface PluginData {

  val saveName: String

  val updaterSerializer: KSerializer<Unit>

  fun onValueChanged(value: Value<*>)

  val serializersModule: SerializersModule // 该属性在 2.0 增加, 但在 2.11 才正式支持并删除 @MiraiExperimentalApi

  fun onInit(owner: PluginDataHolder, storage: PluginDataStorage)
}
