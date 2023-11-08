@file:Suppress(
  "INVISIBLE_REFERENCE",
  "INVISIBLE_MEMBER",
)
package com.diyigemt.arona.config

import com.diyigemt.arona.config.internal.*
import com.diyigemt.arona.config.internal.ByteValueImpl
import com.diyigemt.arona.config.internal.IntValueImpl
import com.diyigemt.arona.config.internal.LongValueImpl
import com.diyigemt.arona.config.internal.ShortValueImpl
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
public inline fun <reified T>
    PluginData.value(apply: T.() -> Unit = {}): SerializerAwareValue<@kotlin.internal.Exact T> =
  valueImpl<T>(typeOf<T>(), T::class).also { it.value.apply() }

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <T> PluginData.valueImpl(type: KType, classifier: KClass<*>): SerializerAwareValue<T> =
  valueFromKType(type, classifier.run { objectInstance ?: createInstanceSmart() } as T)

/**
 * 通过一个特定的 [KType] 创建 [Value], 并设置初始值.
 *
 * 对于 [Map], [Set], [List] 等标准库类型, 这个函数会尝试构造 [LinkedHashMap], [LinkedHashSet], [ArrayList] 等相关类型.
 * 而对于自定义数据类型, 本函数只会反射获取 [objectInstance][KClass.objectInstance] 或使用*无参构造器*构造实例.
 *
 * @param T 具体化参数类型 T. 仅支持:
 * - 基础数据类型, [String]
 * - 标准库集合类型 ([List], [Map], [Set])
 * - 标准库数据类型 ([Map.Entry], [Pair], [Triple])
 * - 使用 [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) 的 [Serializable] 标记的类
 */
@Suppress("UNCHECKED_CAST")
public fun <T> PluginData.valueFromKType(type: KType, default: T): SerializerAwareValue<T> =
  (valueFromKTypeImpl(type) as SerializerAwareValue<Any?>).apply { this.value = default } as SerializerAwareValue<T>
