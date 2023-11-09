@file:Suppress(
  "INVISIBLE_REFERENCE",
  "INVISIBLE_MEMBER",
)

package com.diyigemt.arona.config

import com.diyigemt.arona.config.internal.*
import com.diyigemt.arona.utils.map
import kotlinx.serialization.KSerializer
import kotlin.internal.LowPriorityInOverloadResolution
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf


fun PluginData.value(default: Byte): SerializerAwareValue<Byte> = valueImpl(default)
fun PluginData.value(default: Short): SerializerAwareValue<Short> = valueImpl(default)
fun PluginData.value(default: Int): SerializerAwareValue<Int> = valueImpl(default)
fun PluginData.value(default: Long): SerializerAwareValue<Long> = valueImpl(default)
fun PluginData.value(default: Float): SerializerAwareValue<Float> = valueImpl(default)
fun PluginData.value(default: Double): SerializerAwareValue<Double> = valueImpl(default)
fun PluginData.value(default: Char): SerializerAwareValue<Char> = valueImpl(default)
fun PluginData.value(default: Boolean): SerializerAwareValue<Boolean> = valueImpl(default)
fun PluginData.value(default: String): SerializerAwareValue<String> = valueImpl(default)
internal fun PluginData.valueImpl(default: Byte): SerializerAwareValue<Byte> = ByteValueImpl(default)
internal fun PluginData.byteValueImpl(): SerializerAwareValue<Byte> = ByteValueImpl()
internal fun PluginData.valueImpl(default: Short): SerializerAwareValue<Short> = ShortValueImpl(default)
internal fun PluginData.shortValueImpl(): SerializerAwareValue<Short> = ShortValueImpl()
internal fun PluginData.valueImpl(default: Int): SerializerAwareValue<Int> = IntValueImpl(default)
internal fun PluginData.intValueImpl(): SerializerAwareValue<Int> = IntValueImpl()
internal fun PluginData.valueImpl(default: Long): SerializerAwareValue<Long> = LongValueImpl(default)
internal fun PluginData.longValueImpl(): SerializerAwareValue<Long> = LongValueImpl()
internal fun PluginData.valueImpl(default: Float): SerializerAwareValue<Float> = FloatValueImpl(default)
internal fun PluginData.floatValueImpl(): SerializerAwareValue<Float> = FloatValueImpl()
internal fun PluginData.valueImpl(default: Double): SerializerAwareValue<Double> = DoubleValueImpl(default)
internal fun PluginData.doubleValueImpl(): SerializerAwareValue<Double> = DoubleValueImpl()
internal fun PluginData.valueImpl(default: Char): SerializerAwareValue<Char> = CharValueImpl(default)
internal fun PluginData.charValueImpl(): SerializerAwareValue<Char> = CharValueImpl()
internal fun PluginData.valueImpl(default: Boolean): SerializerAwareValue<Boolean> = BooleanValueImpl(default)
internal fun PluginData.booleanValueImpl(): SerializerAwareValue<Boolean> = BooleanValueImpl()
internal fun PluginData.valueImpl(default: String): SerializerAwareValue<String> = StringValueImpl(default)
internal fun PluginData.stringValueImpl(): SerializerAwareValue<String> = StringValueImpl()

internal class LazyReferenceValueImpl<T> : Value<T> {
  private var initialied: Boolean = false
  private var valueField: T? = null

  @Suppress("UNCHECKED_CAST")
  override var value: T
    get() {
      check(initialied) { "Internal error: LazyReferenceValueImpl.valueField isn't initialized" }
      return valueField as T
    }
    set(value) {
      initialied = true
      valueField = value
    }

  override fun toString(): String {
    return valueField.toString()
  }

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other?.javaClass != this.javaClass) return false

    other as LazyReferenceValueImpl<*>
    return other.valueField == valueField
  }

  override fun hashCode(): Int {
    return valueField?.hashCode() ?: 0
  }
}

@LowPriorityInOverloadResolution
inline fun <reified T> PluginData.value(
  default: T,
  crossinline apply: T.() -> Unit = {},
): SerializerAwareValue<@kotlin.internal.Exact T> {
  return valueFromKType(typeOf<T>(), default).also { it.value.apply() }
}

@LowPriorityInOverloadResolution
inline fun <reified T>
    PluginData.value(apply: T.() -> Unit = {}): SerializerAwareValue<@kotlin.internal.Exact T> =
  valueImpl<T>(typeOf<T>(), T::class).also { it.value.apply() }

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <T> PluginData.valueImpl(type: KType, classifier: KClass<*>): SerializerAwareValue<T> =
  valueFromKType(type, classifier.run { objectInstance ?: createInstanceSmart() } as T)

@Suppress("UNCHECKED_CAST")
fun <T> PluginData.valueFromKType(type: KType, default: T): SerializerAwareValue<T> =
  (valueFromKTypeImpl(type) as SerializerAwareValue<Any?>).apply { this.value = default } as SerializerAwareValue<T>

class SerializableValue<T>(
  @JvmField internal val delegate: Value<T>,
  override val serializer: KSerializer<Unit>,
) : Value<T> by delegate, SerializerAwareValue<T> {
  override fun toString(): String = delegate.toString()

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other?.javaClass != this.javaClass) return false

    @Suppress("UNCHECKED_CAST")
    other as SerializableValue<T>
    if (other.delegate != this.delegate) return false
    // if (other.serializer != this.serializer) return false
    // TODO: 2020/9/9 serializers should be checked here, but it will cause incomparable issue when putting a SerializableValue as a Key
    return true
  }

  override fun hashCode(): Int {
    @Suppress("UnnecessaryVariable", "CanBeVal")
    var result = delegate.hashCode()
    // result = 31 * result + serializer.hashCode()
    // TODO: 2020/9/9 serializers should be checked here, but it will cause incomparable issue when putting a SerializableValue as a Key
    return result
  }

  companion object {
    @JvmStatic
    @JvmName("create")
    fun <T> Value<T>.serializableValueWith(
      serializer: KSerializer<T>,
    ): SerializableValue<T> {
      return SerializableValue(
        this,
        serializer.map(serializer = {
          this.value
        }, deserializer = { this.setValueBySerializer(it) })
      )
    }
  }
}
