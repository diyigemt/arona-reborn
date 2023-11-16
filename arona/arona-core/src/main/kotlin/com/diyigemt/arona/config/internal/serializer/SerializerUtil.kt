package com.diyigemt.arona.config.internal.serializer

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializerOrNull

@OptIn(InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
internal fun IYamlDynamicSerializer.serializeImpl(
  encoder: Encoder,
  value: Any
) = when (value::class.java) {
  Byte::class.java -> encoder.encodeSerializableValue(Byte.serializer(), value as Byte)
  Short::class.java -> encoder.encodeSerializableValue(Short.serializer(), value as Short)
  Int::class.java -> encoder.encodeSerializableValue(Int.serializer(), value as Int)
  Long::class.java -> encoder.encodeSerializableValue(Long.serializer(), value as Long)
  Float::class.java -> encoder.encodeSerializableValue(Float.serializer(), value as Float)
  Double::class.java -> encoder.encodeSerializableValue(Double.serializer(), value as Double)
  Char::class.java -> encoder.encodeSerializableValue(Char.serializer(), value as Char)
  String::class.java -> encoder.encodeSerializableValue(String.serializer(), value as String)
  Boolean::class.java -> encoder.encodeSerializableValue(Boolean.serializer(), value as Boolean)

  IntArray::class.java -> encoder.encodeSerializableValue(IntArraySerializer(), value as IntArray)
  ShortArray::class.java -> encoder.encodeSerializableValue(ShortArraySerializer(), value as ShortArray)
  ByteArray::class.java -> encoder.encodeSerializableValue(ByteArraySerializer(), value as ByteArray)
  LongArray::class.java -> encoder.encodeSerializableValue(LongArraySerializer(), value as LongArray)
  CharArray::class.java -> encoder.encodeSerializableValue(CharArraySerializer(), value as CharArray)
  FloatArray::class.java -> encoder.encodeSerializableValue(FloatArraySerializer(), value as FloatArray)
  DoubleArray::class.java -> encoder.encodeSerializableValue(DoubleArraySerializer(), value as DoubleArray)
  BooleanArray::class.java -> encoder.encodeSerializableValue(BooleanArraySerializer(), value as BooleanArray)

  Pair::class.java -> encoder.encodeSerializableValue(YamlDynamicPairSerializer, value as Pair<Any?, Any?>)
  Triple::class.java -> encoder.encodeSerializableValue(YamlDynamicTripleSerializer, value as Triple<Any?, Any?, Any?>)

  Array<Any>::class.java -> encoder.encodeSerializableValue(AnyTypedArraySerializer, value as Array<Any?>)

  Array<Int>::class.java -> encoder.encodeSerializableValue(IntTypedArraySerializer, value as Array<Int>)
  Array<Double>::class.java -> encoder.encodeSerializableValue(DoubleTypedArraySerializer, value as Array<Double>)
  Array<Float>::class.java -> encoder.encodeSerializableValue(FloatTypedArraySerializer, value as Array<Float>)
  Array<Byte>::class.java -> encoder.encodeSerializableValue(ByteTypedArraySerializer, value as Array<Byte>)
  Array<Short>::class.java -> encoder.encodeSerializableValue(ShortTypedArraySerializer, value as Array<Short>)
  Array<Char>::class.java -> encoder.encodeSerializableValue(CharTypedArraySerializer, value as Array<Char>)
  Array<String>::class.java -> encoder.encodeSerializableValue(StringTypedArraySerializer, value as Array<String>)
  Array<Long>::class.java -> encoder.encodeSerializableValue(LongTypedArraySerializer, value as Array<Long>)

  else -> when (value) {
    is Map<*, *> -> encoder.encodeSerializableValue(YamlDynamicMapSerializer, value as Map<Any?, Any?>)
    is List<*> -> encoder.encodeSerializableValue(YamlDynamicListSerializer, value as List<Any?>)
    is Map.Entry<*, *> -> encoder.encodeSerializableValue(YamlMapEntrySerializer, value as Map.Entry<Any?, Any?>)
    else -> encoder.encodeSerializableValue(
      serializer = value::class.serializerOrNull()
          as? KSerializer<Any>?
        ?: error(
          "Cannot find serializer for ${value.classSimpleName()}. Please use specify serializers manually."
        ),
      value = value
    )
  }
}




private val LONG_AS_DOUBLE_RANGE = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()
private val INT_AS_DOUBLE_RANGE = Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()

internal fun String.adjustDynamicString(quoted: Boolean): Any {
  if (quoted) return this

  return when (this) {
    "true", "TRUE" -> true
    "false", "FALSE" -> false
    else -> {
      val double = this.toDoubleOrNull() ?: return this
      if (this.contains('.')) return double // explicit dot, then it should be double
      when (double) {
        in INT_AS_DOUBLE_RANGE -> double.toInt()
        in LONG_AS_DOUBLE_RANGE -> double.toLong()
        else -> double
      }
    }
  }
}


internal interface IYamlDynamicSerializer

@Suppress("RemoveExplicitTypeArguments") // compiler bug
internal object AnyTypedArraySerializer : KSerializer<Array<Any?>> by ArraySerializer<Any, Any?>(YamlNullableDynamicSerializer)
internal object YamlDynamicPairSerializer : KSerializer<Pair<Any?, Any?>> by PairSerializer(
  YamlNullableDynamicSerializer,
  YamlNullableDynamicSerializer
)

internal object YamlDynamicTripleSerializer :
  KSerializer<Triple<Any?, Any?, Any?>> by TripleSerializer(
    YamlNullableDynamicSerializer,
    YamlNullableDynamicSerializer,
    YamlNullableDynamicSerializer
  )

internal object YamlMapEntrySerializer : KSerializer<Map.Entry<Any?, Any?>> by MapEntrySerializer(
  YamlNullableDynamicSerializer,
  YamlNullableDynamicSerializer
)

internal object IntTypedArraySerializer : KSerializer<Array<Int>> by ArraySerializer(Int.serializer())
internal object DoubleTypedArraySerializer : KSerializer<Array<Double>> by ArraySerializer(Double.serializer())
internal object FloatTypedArraySerializer : KSerializer<Array<Float>> by ArraySerializer(Float.serializer())
internal object ByteTypedArraySerializer : KSerializer<Array<Byte>> by ArraySerializer(Byte.serializer())
internal object ShortTypedArraySerializer : KSerializer<Array<Short>> by ArraySerializer(Short.serializer())
internal object CharTypedArraySerializer : KSerializer<Array<Char>> by ArraySerializer(Char.serializer())
internal object StringTypedArraySerializer : KSerializer<Array<String>> by ArraySerializer(String.serializer())
internal object LongTypedArraySerializer : KSerializer<Array<Long>> by ArraySerializer(Long.serializer())

internal object YamlDynamicMapSerializer : KSerializer<Map<Any?, Any?>> by MapSerializer(
  YamlNullableDynamicSerializer,
  YamlNullableDynamicSerializer
)

internal object YamlDynamicListSerializer : KSerializer<List<Any?>> by ListSerializer(YamlNullableDynamicSerializer)

@Suppress("NOTHING_TO_INLINE")
internal inline fun Any.classSimpleName(): String? {
  return this::class.simpleName
}
