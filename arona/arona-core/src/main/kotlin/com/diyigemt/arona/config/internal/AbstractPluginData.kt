@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "EXPOSED_SUPER_CLASS", "NOTHING_TO_INLINE", "unused")

package com.diyigemt.arona.config.internal

import com.diyigemt.arona.config.PluginData
import com.diyigemt.arona.config.PluginDataHolder
import com.diyigemt.arona.config.PluginDataStorage
import com.diyigemt.arona.utils.valueName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KProperty


abstract class AbstractPluginData : PluginData, PluginDataImpl() {
  /**
   * 这个 [PluginData] 保存时使用的名称.
   */
  abstract override val saveName: String

  internal val valueNodes: MutableList<ValueNode<*>> = mutableListOf()

  open fun <T : SerializerAwareValue<*>> track(
    value: T,
    valueName: String,
    annotations: List<Annotation>,
  ): T =
    value.apply { this@AbstractPluginData.valueNodes.add(ValueNode(valueName, this, annotations, this.serializer)) }

  operator fun <T : SerializerAwareValue<*>> T.provideDelegate(
    thisRef: Any?,
    property: KProperty<*>,
  ): T = track(this, property.valueName, property.getAnnotationListForValueSerialization())

  final override val updaterSerializer: KSerializer<Unit>
    get() = super.updaterSerializer

  override val serializersModule: SerializersModule get() = EmptySerializersModule()

  override fun onValueChanged(value: Value<*>) {
    // no-op by default
  }

  override fun onInit(owner: PluginDataHolder, storage: PluginDataStorage) {
    // no-op by default
  }

  data class ValueNode<T>(
    val valueName: String,
    val value: Value<out T>,
    val annotations: List<Annotation>,
    val updaterSerializer: KSerializer<Unit>,
  )
}


fun <T> AbstractPluginData.findBackingFieldValue(property: KProperty<T>): Value<out T>? =
  findBackingFieldValue(property.valueName)

fun <T> AbstractPluginData.findBackingFieldValue(propertyValueName: String): Value<out T>? {
  @Suppress("UNCHECKED_CAST")
  return this.valueNodes.find { it.valueName == propertyValueName }?.value as Value<out T>?
}

fun <T> AbstractPluginData.findBackingFieldValueNode(property: KProperty<T>): AbstractPluginData.ValueNode<out T>? {
  @Suppress("UNCHECKED_CAST")
  return this.valueNodes.find { it.valueName == property.name } as AbstractPluginData.ValueNode<out T>?
}
