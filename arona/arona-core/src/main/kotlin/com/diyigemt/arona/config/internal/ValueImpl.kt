@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "unused", "NOTHING_TO_INLINE", "INAPPLICABLE_JVM_NAME")

package com.diyigemt.arona.config.internal

import com.diyigemt.arona.config.*
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.StringFormat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

interface Value<T> : ReadWriteProperty<Any?, T> {
  @get:JvmName("get")
  @set:JvmName("set")
  var value: T

  @JvmSynthetic // avoid ambiguity with property `value`
  override operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

  @JvmSynthetic
  override operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
    this.value = value
  }
}

internal abstract class AbstractValueImpl<T> : Value<T> {
  open fun setValueBySerializer(value: T) {
    this.value = value
  }
}

interface SerializerAwareValue<T> : Value<T> {
  val serializer: KSerializer<Unit>

  companion object {
    @JvmStatic
    fun <T> SerializerAwareValue<T>.serialize(format: StringFormat): String {
      return format.encodeToString(this.serializer, Unit)
    }

    @JvmStatic
    fun <T> SerializerAwareValue<T>.serialize(format: BinaryFormat): ByteArray {
      return format.encodeToByteArray(this.serializer, Unit)
    }

    @JvmStatic
    fun <T> SerializerAwareValue<T>.deserialize(format: StringFormat, string: String) {
      format.decodeFromString(this.serializer, string)
    }

    @JvmStatic
    fun <T> SerializerAwareValue<T>.deserialize(format: BinaryFormat, bytes: ByteArray) {
      format.decodeFromByteArray(this.serializer, bytes)
    }
  }
}

internal class ByteValueImpl : SerializerAwareValue<Byte>, KSerializer<Unit>, AbstractValueImpl<Byte> {
  constructor()
  constructor(default: Byte) {
    _value = default
  }

  private var _value: Byte? = null
  override var value: Byte
    get() = _value ?: error("ByteValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Byte.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Byte.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Byte.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "ByteValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is ByteValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class ShortValueImpl : SerializerAwareValue<Short>, KSerializer<Unit>, AbstractValueImpl<Short> {
  constructor()
  constructor(default: Short) {
    _value = default
  }

  private var _value: Short? = null
  override var value: Short
    get() = _value ?: error("ShortValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Short.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Short.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Short.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "ShortValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is ShortValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class IntValueImpl : SerializerAwareValue<Int>, KSerializer<Unit>, AbstractValueImpl<Int> {
  constructor()
  constructor(default: Int) {
    _value = default
  }

  private var _value: Int? = null
  override var value: Int
    get() = _value ?: error("IntValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Int.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Int.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Int.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "IntValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is IntValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class LongValueImpl : SerializerAwareValue<Long>, KSerializer<Unit>, AbstractValueImpl<Long> {
  constructor()
  constructor(default: Long) {
    _value = default
  }

  private var _value: Long? = null
  override var value: Long
    get() = _value ?: error("LongValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Long.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Long.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Long.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "LongValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is LongValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class FloatValueImpl : SerializerAwareValue<Float>, KSerializer<Unit>, AbstractValueImpl<Float> {
  constructor()
  constructor(default: Float) {
    _value = default
  }

  private var _value: Float? = null
  override var value: Float
    get() = _value ?: error("FloatValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Float.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Float.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Float.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "FloatValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is FloatValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class DoubleValueImpl : SerializerAwareValue<Double>, KSerializer<Unit>, AbstractValueImpl<Double> {
  constructor()
  constructor(default: Double) {
    _value = default
  }

  private var _value: Double? = null
  override var value: Double
    get() = _value ?: error("DoubleValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Double.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Double.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Double.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "DoubleValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is DoubleValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class CharValueImpl : SerializerAwareValue<Char>, KSerializer<Unit>, AbstractValueImpl<Char> {
  constructor()
  constructor(default: Char) {
    _value = default
  }

  private var _value: Char? = null
  override var value: Char
    get() = _value ?: error("LongValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Char.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Char.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Char.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "CharValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is CharValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class BooleanValueImpl : SerializerAwareValue<Boolean>, KSerializer<Unit>, AbstractValueImpl<Boolean> {
  constructor()
  constructor(default: Boolean) {
    _value = default
  }

  private var _value: Boolean? = null
  override var value: Boolean
    get() = _value ?: error("BooleanValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = Boolean.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = Boolean.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(Boolean.serializer().deserialize(decoder))
  override fun toString(): String = _value?.toString() ?: "BooleanValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is BooleanValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

internal class StringValueImpl : SerializerAwareValue<String>, KSerializer<Unit>, AbstractValueImpl<String> {
  constructor()
  constructor(default: String) {
    _value = default
  }

  private var _value: String? = null
  override var value: String
    get() = _value ?: error("StringValue.value should be initialized before get.")
    set(value) {
      if (value != this._value) {
        if (this._value == null) {
          this._value = value
        } else {
          this._value = value
        }
      }
    }
  override val serializer: KSerializer<Unit> get() = this
  override val descriptor: SerialDescriptor get() = String.serializer().descriptor
  override fun serialize(encoder: Encoder, value: Unit) = String.serializer().serialize(encoder, this.value)
  override fun deserialize(decoder: Decoder) = setValueBySerializer(String.serializer().deserialize(decoder))
  override fun toString(): String = _value ?: "StringValue.value not yet initialized."
  override fun equals(other: Any?): Boolean =
    other is StringValueImpl && other::class.java == this::class.java && other._value == this._value

  override fun hashCode(): Int {
    val value = _value
    return if (value == null) 1
    else value.hashCode() * 31
  }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> PluginData.valueImplPrimitive(kClass: KClass<T>): SerializerAwareValue<T>? {
  return when (kClass) {

    Byte::class -> byteValueImpl()
    Short::class -> shortValueImpl()
    Int::class -> intValueImpl()
    Long::class -> longValueImpl()
    Float::class -> floatValueImpl()
    Double::class -> doubleValueImpl()
    Char::class -> charValueImpl()
    Boolean::class -> booleanValueImpl()
    String::class -> stringValueImpl()

    else -> error("Internal error: unexpected type passed: ${kClass.qualifiedName}")
  } as SerializerAwareValue<T>?
}

internal fun <T> Value<T>.setValueBySerializer(value: T) {
  if (this is SerializableValue<T>) {
    return this.delegate.setValueBySerializer(value)
  }
  this.castOrInternalError<AbstractValueImpl<T>>().setValueBySerializer(value)
}

@OptIn(ExperimentalContracts::class)
@Suppress("UNCHECKED_CAST")
internal inline fun <reified R> Any.castOrInternalError(): R {
  contract {
    returns() implies (this@castOrInternalError is R)
  }
  return (this as? R) ?: error("Internal error: ${this::class} cannot be casted to ${R::class}")
}
