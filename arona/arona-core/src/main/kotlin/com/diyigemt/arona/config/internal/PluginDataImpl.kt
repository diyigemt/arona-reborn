@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS")
@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.config.internal

import com.diyigemt.arona.config.PluginData
import com.diyigemt.arona.config.internal.AbstractPluginData.ValueNode
import com.diyigemt.arona.config.internal.serializer.YamlNullableDynamicSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.lang.reflect.Constructor
import kotlin.reflect.KAnnotatedElement

internal abstract class PluginDataImpl {
  init {
    @Suppress("LeakingThis")
    check(this is AbstractPluginData)
  }

  private fun findNodeInstance(name: String): ValueNode<*>? {
    check(this is AbstractPluginData)
    return valueNodes.firstOrNull { it.valueName == name }
  }

  internal open val updaterSerializer: KSerializer<Unit> = object : KSerializer<Unit> {
    override val descriptor: SerialDescriptor by lazy {
      check(this@PluginDataImpl is AbstractPluginData)
      kotlinx.serialization.descriptors.buildClassSerialDescriptor((this@PluginDataImpl as PluginData).saveName) {
        for (valueNode in valueNodes) valueNode.run {
          element(valueName, updaterSerializer.descriptor, annotations = annotations, isOptional = true)
        }
      }
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder) {
      val descriptor = descriptor
      with(decoder.beginStructure(descriptor)) {
        if (decodeSequentially()) {
          var index = 0
          repeat(decodeCollectionSize(descriptor)) {
            val valueName = decodeSerializableElement(descriptor, index++, String.serializer())
            val node = findNodeInstance(valueName)
            if (node == null) {
              decodeSerializableElement(descriptor, index++, YamlNullableDynamicSerializer)
            } else {
              decodeSerializableElement(descriptor, index++, node.updaterSerializer)
            }
          }
        } else {
          outerLoop@ while (true) {
            innerLoop@ while (true) {
              val index = decodeElementIndex(descriptor)
              if (index == CompositeDecoder.DECODE_DONE) {
                //check(valueName == null) { "name must be null at this moment." }
                break@outerLoop
              }

              val node = findNodeInstance(descriptor.getElementName(index))
              if (node == null) {
                decodeSerializableElement(descriptor, index, YamlNullableDynamicSerializer)
              } else {
                decodeSerializableElement(descriptor, index, node.updaterSerializer)
              }
              break@innerLoop
            }

          }
        }
        endStructure(descriptor)
      }
    }

    @Suppress("UNCHECKED_CAST")
    override fun serialize(encoder: Encoder, value: Unit) {
      check(this@PluginDataImpl is AbstractPluginData)

      val descriptor = descriptor
      with(encoder.beginStructure(descriptor)) {
        repeat(descriptor.elementsCount) { index ->
          encodeSerializableElement(
            descriptor,
            index,
            valueNodes.find { it.valueName == descriptor.getElementName(index) }?.updaterSerializer
              ?: error("Cannot find a serializer for ${descriptor.getElementName(index)}"),
            Unit
          )
        }
        endStructure(descriptor)
      }
    }

  }
}

internal fun KAnnotatedElement.getAnnotationListForValueSerialization(): List<Annotation> {
  return this.annotations.mapNotNull {
    when (it) {
      is SerialName -> it
      else -> it
    }
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T : Any?> Constructor<T>.invoke(vararg args: Any?): T = this.newInstance(*args)

internal inline fun <reified T : Any> findAnnotationImplementationClassConstructor(): Constructor<out T>? {
  @Suppress("UNCHECKED_CAST")
  return T::class.nestedClasses
    .find { it.simpleName?.endsWith("Impl") == true }?.java?.run {
      constructors.singleOrNull()
    } as Constructor<out T>?
}
