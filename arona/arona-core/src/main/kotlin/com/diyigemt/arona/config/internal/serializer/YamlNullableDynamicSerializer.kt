package com.diyigemt.arona.config.internal.serializer

import com.charleskorn.kaml.YamlInput
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
  override fun deserialize(decoder: Decoder): Any? = (decoder as YamlInput).decodeNullableSerializableValue(decoder.yaml.serializersModule.serializer<Unit>())

  @OptIn(ExperimentalSerializationApi::class)
  override fun serialize(encoder: Encoder, value: Any?) {
    if (value == null) {
      encoder.encodeNullableSerializableValue(String.serializer(), value)
    } else serializeImpl(encoder, value)
  }
}
