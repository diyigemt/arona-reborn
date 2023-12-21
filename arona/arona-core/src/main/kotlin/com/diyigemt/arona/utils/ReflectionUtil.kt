@file:Suppress("unused", "unused_parameters")

package com.diyigemt.arona.utils

import kotlinx.serialization.SerialName
import org.reflections.ReflectionUtils
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ConfigurationBuilder
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation

/**
 * 被标记的类/方法不会被扫描
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
internal annotation class ReflectionIgnore
object ReflectionUtil : ReflectionUtils() {
  private val reflections by lazy {
    Reflections(ConfigurationBuilder().apply {
      forPackage("com.diyigemt.arona")
      setScanners(Scanners.TypesAnnotated, Scanners.SubTypes)
    })
  }

  fun <T : Annotation> scanTypeAnnotatedClass(annotation: KClass<T>): List<String> {
    val ignores = reflections
      .get(Scanners.TypesAnnotated.with(ReflectionIgnore::class.java))
    val all = reflections
      .get(Scanners.TypesAnnotated.with(annotation.java))
      .filterNot { ignores.contains(it) }
    return all
  }


  fun <T : Annotation> scanTypeAnnotatedClass(scanner: Reflections, annotation: KClass<T>): List<String> {
    val ignores = scanner
      .get(Scanners.TypesAnnotated.with(ReflectionIgnore::class.java))
    val all = scanner
      .get(Scanners.TypesAnnotated.with(annotation.java))
      .filterNot { ignores.contains(it) }
    return all
  }

  inline fun <reified T : Annotation> scanMethodWithAnnotated(clazz: KClass<*>) = clazz
    .declaredFunctions
    .filterNot { it.hasAnnotation<ReflectionIgnore>() }
    .filter { it.hasAnnotation<T>() }

  fun <T : Any> scanInterfacePetClass(clazz: KClass<T>) = reflections.get(Scanners.SubTypes.with(clazz.java))
  fun <T : Any> scanInterfacePetClass(scanner: Reflections, clazz: KClass<T>) =
    scanner.get(Scanners.SubTypes.with(clazz.java))

  fun <T : Annotation> scanTypeAnnotatedObjectInstance(clazz: KClass<T>) = runCatching {
    scanTypeAnnotatedClass(clazz).mapNotNull {
      Class.forName(it).kotlin.objectInstance
    }
  }.getOrElse { error ->
    error.printStackTrace()
    listOf()
  }

  fun <T : Annotation> scanTypeAnnotatedObjectInstance(scanner: Reflections, clazz: KClass<T>) = runCatching {
    scanTypeAnnotatedClass(scanner, clazz).mapNotNull {
      Class.forName(it).kotlin.objectInstance
    }
  }.getOrElse { error ->
    error.printStackTrace()
    listOf()
  }

  @Suppress("UNCHECKED_CAST")
  fun <T : Any> scanInterfacePetObjectInstance(clazz: KClass<T>) = runCatching {
    scanInterfacePetClass(clazz).mapNotNull {
      Class.forName(it).kotlin.objectInstance
    } as List<T>
  }.getOrElse { error ->
    error.printStackTrace()
    listOf()
  }

  @Suppress("UNCHECKED_CAST")
  fun <T : Any> scanInterfacePetObjectInstance(scanner: Reflections, clazz: KClass<T>) = runCatching {
    scanInterfacePetClass(scanner, clazz).mapNotNull {
      Class.forName(it).kotlin.objectInstance
    } as List<T>
  }.getOrElse { error ->
    error.printStackTrace()
    listOf()
  }
}

internal val KClass<*>.qualifiedNameOrTip: String get() = this.qualifiedName ?: "<anonymous class>"

fun <T : Any> KClass<T>.createInstanceOrNull(): T? {
  objectInstance?.let { return it }
  val noArgsConstructor = constructors.singleOrNull { it.parameters.all(KParameter::isOptional) }
    ?: return null

  return noArgsConstructor.callBy(emptyMap())
}

@Suppress("UNCHECKED_CAST")
internal fun KType.classifierAsKClass() = when (val t = classifier) {
  is KClass<*> -> t
  else -> error("Only KClass supported as classifier, got $t")
} as KClass<Any>

internal val KProperty<*>.valueName: String get() = this.findAnnotation<SerialName>()?.value ?: this.name
