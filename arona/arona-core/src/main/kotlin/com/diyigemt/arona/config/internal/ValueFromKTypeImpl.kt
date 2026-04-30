package com.diyigemt.arona.config.internal

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

@Suppress("UNCHECKED_CAST")
internal fun PluginData.valueFromKTypeImpl(type: KType): SerializerAwareValue<*> {
  // 直接 as? + error 而非 require(... is ...) + 隐式 smart cast: K2.3 收紧了 smart cast 在 lambda
  // 捕获边界的传播 (mapInitializer 闭包以前要靠 cast<KClass<*>>() workaround), 此模式让 classifier
  // 的非空 KClass<*> 类型在外层和内层 lambda 都直接成立, 顺手消掉 SMARTCAST_IMPOSSIBLE / UnsafeCall
  // 两个 @Suppress.
  val classifier = type.classifier as? KClass<*>
    ?: error("Only KClass classifiers are supported for PluginData value, got ${type.classifier}")

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
      requireKClassArgument(type, index = 0, kind = "key")
      requireKClassArgument(type, index = 1, kind = "value")
      return createCompositeMapValueImpl<Any?, Any?>(
        mapInitializer = {
          if (classifier.isSubclassOf(ConcurrentMap::class)) {
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
      requireKClassArgument(type, index = 0, kind = "element")
      return createCompositeListValueImpl<Any?> { v -> valueFromKType(type.arguments[0].type!!, v) }
        .serializableValueWith(serializersModule.serializerTencentBot(type) as KSerializer<List<Any?>>)
    }

    MutableSet::class,
    Set::class,
    LinkedHashSet::class,
    HashSet::class,
    -> {
      requireKClassArgument(type, index = 0, kind = "element")
      return createCompositeSetValueImpl<Any?> { v -> valueFromKType(type.arguments[0].type!!, v) }
        .serializableValueWith(serializersModule.serializerTencentBot(type) as KSerializer<Set<Any?>>)
    }

    else -> {
      val serializer = serializersModule.serializerTencentBot(type)
      return LazyReferenceValueImpl<Any?>().serializableValueWith(serializer)
    }
  }
}

private fun requireKClassArgument(type: KType, index: Int, kind: String) {
  val classifier = type.arguments[index].type?.classifier
  require(classifier is KClass<*>) {
    "PluginData value $kind classifier must be a KClass<*>, got $classifier in $type"
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
