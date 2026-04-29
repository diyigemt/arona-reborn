package com.diyigemt.arona.config.internal.serializer

import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// TODO handle deserialize
object YamlNullableDynamicSerializer : KSerializer<Any?>, IYamlDynamicSerializer {
  @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
  override val descriptor: SerialDescriptor = buildSerialDescriptor("YamlNullableDynamic", SerialKind.CONTEXTUAL)

  @OptIn(ExperimentalSerializationApi::class)
  override fun deserialize(decoder: Decoder): Any? =
    // Kaml 0.6x+ 起 YamlInput 不再是稳定的公共 cast 目标; 改用通用 Decoder API + decoder.serializersModule,
    // 行为对 Yaml/Json 都等价 (取上下文 module 解 nullable Unit), 同时摆脱对 Kaml 内部类型的耦合.
    decoder.decodeNullableSerializableValue(decoder.serializersModule.serializer<Unit>())

  @OptIn(ExperimentalSerializationApi::class)
  override fun serialize(encoder: Encoder, value: Any?) {
    if (value == null) {
      encoder.encodeNullableSerializableValue(String.serializer(), value)
    } else serializeImpl(encoder, value)
  }
}
