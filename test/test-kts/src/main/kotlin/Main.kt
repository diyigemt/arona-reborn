package com.diyigemt

import kotlin.script.experimental.annotations.KotlinScript
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

// The KotlinScript annotation marks a class that can serve as a reference to the script definition for
// `createJvmCompilationConfigurationFromTemplate` call as well as for the discovery mechanism
// The marked class also become the base class for defined script type (unless redefined in the configuration)
@KotlinScript(
  // file name extension by which this script type is recognized by mechanisms built into scripting compiler plugin
  // and IDE support, it is recommendend to use double extension with the last one being "kts", so some non-specific
  // scripting support could be used, e.g. in IDE, if the specific support is not installed.
  fileExtension = "kts"
)
// the class is used as the script base class, therefore it should be open or abstract
abstract class SimpleScript

fun evalFile(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {
  val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<SimpleScript> {
    jvm {
      // configure dependencies for compilation, they should contain at least the script base class and
      // its dependencies
      // variant 1: try to extract current classpath and take only a path to the specified "script.jar"
//      dependenciesFromCurrentContext(
//        "script" /* script library jar name (exact or without a version) */
//      )
      // variant 2: try to extract current classpath and use it for the compilation without filtering
      dependenciesFromCurrentContext(wholeClasspath = true)
      // variant 3: try to extract a classpath from a particular classloader (or Thread.contextClassLoader by default)
      // filtering as in the variat 1 is supported too
      dependenciesFromClassloader(classLoader = SimpleScript::class.java.classLoader, wholeClasspath = true)
      // variant 4: explicit classpath
//            updateClasspath(listOf(File("/path/to/jar")))
    }
  }

  return BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfiguration, null)
}

fun main(vararg args: String) {
  if (args.size != 1) {
    println("usage: <app> <script file>")
  } else {
    val scriptFile = File(args[0])
    println("Executing script $scriptFile")

    val res = evalFile(scriptFile)

    res.reports.forEach {
      if (it.severity > ScriptDiagnostic.Severity.DEBUG) {
        println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
      }
    }
  }
}
