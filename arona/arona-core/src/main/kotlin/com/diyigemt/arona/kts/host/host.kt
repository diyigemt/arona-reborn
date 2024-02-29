package com.diyigemt.arona.kts.host

import com.diyigemt.arona.kts.def.COMPILED_SCRIPTS_CACHE_DIR_PROPERTY
import com.diyigemt.arona.kts.def.MainKtsEvaluationConfiguration
import com.diyigemt.arona.kts.def.SimpleMainKtsScript
import java.io.File
import kotlin.reflect.KClass
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

data class ProvidedProperty<T : Any>(val name: String, val type: KClass<out T>, val value: T?)

fun evalFile(scriptFile: File, context: Map<String, Any>, cacheDir: File? = null):
    ResultWithDiagnostics<EvaluationResult> = evalFile(scriptFile, context.map {
  ProvidedProperty(
    it.key, it.value::class, it.value
  )
}, cacheDir)

fun evalFile(scriptFile: File, context: List<ProvidedProperty<*>> = listOf(), cacheDir: File? = null):
    ResultWithDiagnostics<EvaluationResult> =
  withMainKtsCacheDir(cacheDir?.absolutePath ?: "") {
    val scriptDefinition = createJvmCompilationConfigurationFromTemplate<SimpleMainKtsScript>() {
      providedProperties(
        *(context.map { it.name to KotlinType(it.type) }.toTypedArray())
      )
    }

    val evaluationEnv = MainKtsEvaluationConfiguration.with {
      jvm {
        baseClassLoader(this::class.java.classLoader)
      }
      constructorArgs(emptyArray<String>())
      enableScriptsInstancesSharing()
      providedProperties(
        *(context.map { it.name to it.value }.toTypedArray())
      )
    }

    BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), scriptDefinition, evaluationEnv)
  }

private fun <T> withMainKtsCacheDir(value: String?, body: () -> T): T {
  val prevCacheDir = System.getProperty(COMPILED_SCRIPTS_CACHE_DIR_PROPERTY)
  if (value == null) System.clearProperty(COMPILED_SCRIPTS_CACHE_DIR_PROPERTY)
  else System.setProperty(COMPILED_SCRIPTS_CACHE_DIR_PROPERTY, value)
  try {
    return body()
  } finally {
    if (prevCacheDir == null) System.clearProperty(COMPILED_SCRIPTS_CACHE_DIR_PROPERTY)
    else System.setProperty(COMPILED_SCRIPTS_CACHE_DIR_PROPERTY, prevCacheDir)
  }
}
