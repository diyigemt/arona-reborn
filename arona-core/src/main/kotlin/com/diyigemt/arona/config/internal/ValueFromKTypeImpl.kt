package com.diyigemt.arona.config.internal

import com.diyigemt.arona.communication.event.cast
import com.diyigemt.arona.config.LazyReferenceValueImpl
import com.diyigemt.arona.config.PluginData
import com.diyigemt.arona.config.SerializableValue.Companion.serializableValueWith
import com.diyigemt.arona.config.valueFromKType
import com.diyigemt.arona.utils.createInstanceOrNull
import com.diyigemt.arona.utils.qualifiedNameOrTip
import com.diyigemt.arona.utils.serializerTencentBot
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

private fun KClass<*>.isReferencingSamePlatformClass(other: KClass<*>): Boolean {
  return this.qualifiedName == other.qualifiedName // not using .java for
}

internal fun KClass<*>.createInstanceSmart(): Any {
  when {
    isReferencingSamePlatformClass(Array::class) -> return emptyArray<Any?>()
  }
  return when (this) {
    Byte::class -> 0.toByte()
    Short::class -> 0.toShort()
    Int::class -> 0
    Long::class -> 0L
    Float::class -> 0.toFloat()
    Double::class -> 0.0

    Boolean::class -> false

    String::class -> ""

    MutableMap::class,
    Map::class,
    LinkedHashMap::class,
    HashMap::class,
    -> LinkedHashMap<Any?, Any?>()

    MutableList::class,
    List::class,
    ArrayList::class,
    -> ArrayList<Any?>()

    MutableSet::class,
    Set::class,
    LinkedHashSet::class,
    HashSet::class,
    -> LinkedHashSet<Any?>()

    ConcurrentHashMap::class,
    ConcurrentMap::class,
    -> ConcurrentHashMap<Any?, Any?>()

    ByteArray::class -> byteArrayOf()
    BooleanArray::class -> booleanArrayOf()
    ShortArray::class -> shortArrayOf()
    IntArray::class -> intArrayOf()
    LongArray::class -> longArrayOf()
    FloatArray::class -> floatArrayOf()
    DoubleArray::class -> doubleArrayOf()
    CharArray::class -> charArrayOf()

    else -> createInstanceOrNull()
      ?: error("Cannot create instance or find a initial value for ${this.qualifiedNameOrTip}")
  }
}

@Suppress("UnsafeCall", "SMARTCAST_IMPOSSIBLE", "UNCHECKED_CAST")
internal fun PluginData.valueFromKTypeImpl(type: KType): SerializerAwareValue<*> {
  val classifier = type.classifier
  require(classifier is KClass<*>)

  if (classifier.isPrimitiveOrBuiltInSerializableValue()) {
    return valueImplPrimitive(classifier) as SerializerAwareValue<*>
  }

  when (classifier) {
    MutableMap::class,
    Map::class,
    LinkedHashMap::class,
    HashMap::class,
    ConcurrentMap::class,
    ConcurrentHashMap::class,
    -> {
      val keyClass = type.arguments[0].type?.classifier
      require(keyClass is KClass<*>)

      val valueClass = type.arguments[1].type?.classifier
      require(valueClass is KClass<*>)
      return createCompositeMapValueImpl<Any?, Any?>(
        mapInitializer = {
          if (classifier.cast<KClass<*>>().isSubclassOf(ConcurrentMap::class)) {
            ConcurrentHashMap()
          } else {
            null
          }
        },
        kToValue = { k -> valueFromKType(type.arguments[0].type!!, k) },
        vToValue = { v -> valueFromKType(type.arguments[1].type!!, v) }
      ).serializableValueWith(serializersModule.serializerTencentBot(type) as KSerializer<Map<Any?, Any?>>) // erased
    }

    MutableList::class,
    List::class,
    ArrayList::class,
    -> {
      val elementClass = type.arguments[0].type?.classifier
      require(elementClass is KClass<*>)
      return createCompositeListValueImpl<Any?> { v -> valueFromKType(type.arguments[0].type!!, v) }
        .serializableValueWith(serializersModule.serializerTencentBot(type) as KSerializer<List<Any?>>)
    }

    MutableSet::class,
    Set::class,
    LinkedHashSet::class,
    HashSet::class,
    -> {
      val elementClass = type.arguments[0].type?.classifier
      require(elementClass is KClass<*>)
      return createCompositeSetValueImpl<Any?> { v -> valueFromKType(type.arguments[0].type!!, v) }
        .serializableValueWith(serializersModule.serializerTencentBot(type) as KSerializer<Set<Any?>>)
    }

    else -> {
      val serializer = serializersModule.serializerTencentBot(type)
      return LazyReferenceValueImpl<Any?>().serializableValueWith(serializer)
    }
  }
}

internal fun KClass<*>.isPrimitiveOrBuiltInSerializableValue(): Boolean {
  when (this) {
    Byte::class, Short::class, Int::class, Long::class,
    Boolean::class,
    Char::class, String::class,
    -> return true
  }

  return false
}
