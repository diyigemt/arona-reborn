package com.diyigemt.arona.arona.tools

import com.diyigemt.arona.arona.Arona
import org.reflections.ReflectionUtils
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ConfigurationBuilder
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.hasAnnotation

object ReflectionTool : ReflectionUtils() {
  private val reflections by lazy {
    Reflections(ConfigurationBuilder().apply {
      forPackage("com.diyigemt.arona.arona", Arona::class.java.classLoader)
      setScanners(Scanners.TypesAnnotated, Scanners.SubTypes)
    })
  }

  fun <T : Annotation> scanTypeAnnotatedClass(annotation: KClass<T>) =
    reflections.get(Scanners.TypesAnnotated.with(annotation.java))

  fun <T : Annotation> scanTypeAnnotatedClass(scanner: Reflections, annotation: KClass<T>) =
    scanner.get(Scanners.TypesAnnotated.with(annotation.java))

  inline fun <reified T : Annotation> scanMethodWithAnnotated(clazz: KClass<*>) = clazz
    .declaredFunctions.filter { it.hasAnnotation<T>() }

  private fun <T : Any> scanInterfacePetClass(clazz: KClass<T>) = reflections.get(Scanners.SubTypes.with(clazz.java))
  private fun <T : Any> scanInterfacePetClass(scanner: Reflections, clazz: KClass<T>) =
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
