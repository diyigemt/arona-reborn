package com.diyigemt.arona.config.internal.serializer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object YamlNullableDynamicSerializer : KSerializer<Any?>, IYamlDynamicSerializer {
  @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
  override val descriptor: SerialDescriptor = buildSerialDescriptor("YamlNullableDynamic", SerialKind.CONTEXTUAL)

  override fun deserialize(decoder: Decoder): Any? {
    TODO("Not yet implemented")
  }

  @OptIn(ExperimentalSerializationApi::class)
  override fun serialize(encoder: Encoder, value: Any?) {
    if (value == null) {
      encoder.encodeNullableSerializableValue(String.serializer(), value)
    } else serializeImpl(encoder, value)
  }
}
