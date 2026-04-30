@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.diyigemt.arona.utils

import com.diyigemt.arona.communication.event.cast
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass
import kotlin.reflect.KType

@Suppress("UNCHECKED_CAST")
internal fun SerializersModule.serializerTencentBot(type: KType): KSerializer<Any?> {
  fun serializerByKTypeImpl(type: KType): KSerializer<*> {
    val rootClass = type.classifierAsKClass()

    // In Kotlin 1.6.20, `typeOf<Array<Long>>?.classifier` surprisingly gives kotlin.LongArray
    // https://youtrack.jetbrains.com/issue/KT-52170/
    if (type.arguments.size == 1) { // can be typeOf<Array<...>>, so cannot be typeOf<IntArray>
      val result: KSerializer<Any?>? = when (rootClass) {
        ByteArray::class -> ArraySerializer(Byte.serializer()).cast()
        ShortArray::class -> ArraySerializer(Short.serializer()).cast()
        IntArray::class -> ArraySerializer(Int.serializer()).cast()
        LongArray::class -> ArraySerializer(Long.serializer()).cast()
        FloatArray::class -> ArraySerializer(Float.serializer()).cast()
        DoubleArray::class -> ArraySerializer(Double.serializer()).cast()
        CharArray::class -> ArraySerializer(Char.serializer()).cast()
        BooleanArray::class -> ArraySerializer(Boolean.serializer()).cast()
        else -> null
      }

      if (result != null) return result
    }

    this.serializerOrNull(type)?.let { return it } // Kotlin builtin and user-defined
    //  TODO TencentMessage
//    MessageSerializers.serializersModule.serializerOrNull(type)?.let { return it } // Mirai Messages
//    if (type.classifier == Any::class) return if (type.isMarkedNullable) YamlNullableDynamicSerializer else YamlDynamicSerializer as KSerializer<Any?>
    if (type.classifier == Any::class) TODO()

    val typeArguments = type.arguments
      .map { requireNotNull(it.type) { "Star projections in type arguments are not allowed, but had $type" } }
    return when {
      typeArguments.isEmpty() -> this.serializer(type)
      else -> {
        val serializers = typeArguments.map(::serializerTencentBot)
        when (rootClass) {
          Collection::class, List::class, MutableList::class, ArrayList::class -> ListSerializer(serializers[0])
          HashSet::class -> SetSerializer(serializers[0])
          Set::class, MutableSet::class, LinkedHashSet::class -> SetSerializer(serializers[0])
          HashMap::class -> MapSerializer(serializers[0], serializers[1])
          Map::class, MutableMap::class, LinkedHashMap::class -> MapSerializer(
            serializers[0],
            serializers[1]
          )

          Map.Entry::class -> MapEntrySerializer(serializers[0], serializers[1])
          Pair::class -> PairSerializer(serializers[0], serializers[1])
          Triple::class -> TripleSerializer(serializers[0], serializers[1], serializers[2])

//          Any::class -> if (type.isMarkedNullable) YamlNullableDynamicSerializer else YamlDynamicSerializer
          Any::class -> TODO()
          else -> {
            if (rootClass.java.isArray) {
              return ArraySerializer(
                typeArguments[0].classifier as KClass<Any>,
                serializers[0]
              ).cast()
            }
            requireNotNull(rootClass.constructSerializerForGivenTypeArgs(*serializers.toTypedArray())) {
              "Can't find a method to construct serializer for type ${rootClass.simpleName}. " +
                  "Make sure this class is marked as @Serializable or provide serializer explicitly."
            }
          }
        }
      }
    }
  }

  val result = serializerByKTypeImpl(type) as KSerializer<Any>
  return if (type.isMarkedNullable) result.nullable else result.cast()
}

// kotlinx.serialization 1.9 公开了 `serializer(KClass, List<KSerializer<*>>, isNullable)`,
// 内部已经覆盖原本手写反射 lookup 的全部 3 条路径 (Companion.serializer / object INSTANCE.serializer() /
// nested $serializer.INSTANCE), 因此删除手写实现 + 配套的 findObjectSerializer 死代码,
// 顺手消掉旧实现的 INVISIBLE_MEMBER / INVISIBLE_REFERENCE / UNSUPPORTED 三个 @Suppress.
// 仅吞 SerializationException 转回 nullable, 维持上游 requireNotNull(...) { "Can't find a method
// to construct serializer..." } 调用约定; 非预期异常 (反射 / 类型转换边界) 直接抛出, 避免被错误
// 折叠成 "找不到 serializer" 而丢失真实错误.
private fun <T : Any> KClass<T>.constructSerializerForGivenTypeArgs(vararg args: KSerializer<Any?>): KSerializer<T>? =
  try {
    @Suppress("UNCHECKED_CAST")
    serializer(this, args.toList(), isNullable = false) as KSerializer<T>
  } catch (_: SerializationException) {
    null
  }

internal inline fun <E, R> KSerializer<E>.map(
  crossinline serializer: (R) -> E,
  crossinline deserializer: (E) -> R,
): KSerializer<R> {
  return object : KSerializer<R> {
    override val descriptor: SerialDescriptor get() = this@map.descriptor
    override fun deserialize(decoder: Decoder): R = this@map.deserialize(decoder).let(deserializer)
    override fun serialize(encoder: Encoder, value: R) = this@map.serialize(encoder, value.let(serializer))
  }
}
