import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

abstract class GenerateAronaBuildConfig : DefaultTask() {
  @get:Input abstract val pluginId: Property<String>
  @get:Input abstract val pluginName: Property<String>
  @get:Input abstract val pluginAuthor: Property<String>
  @get:Input abstract val pluginVersion: Property<String>
  @get:Input abstract val pluginDescription: Property<String>
  @get:Input abstract val generatedPackage: Property<String>
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  init {
    group = "arona"
    description = "Generate BuildConfig.kt carrying Arona plugin metadata."
  }

  @TaskAction
  fun generate() {
    val rootDir = outputDirectory.get().asFile
    if (rootDir.exists()) rootDir.deleteRecursively()
    val packageName = generatedPackage.get()
    val packageDir = outputDirectory.dir(packageName.replace('.', '/')).get().asFile.toPath()
    Files.createDirectories(packageDir)
    val target = packageDir.resolve("BuildConfig.kt")
    val source = buildString {
      append("package ").append(packageName).append("\n\n")
      append("internal object BuildConfig {\n")
      append("  internal const val ID = ").append(pluginId.get().toKotlinLiteral()).append('\n')
      append("  internal const val NAME = ").append(pluginName.get().toKotlinLiteral()).append('\n')
      append("  internal const val AUTHOR = ").append(pluginAuthor.get().toKotlinLiteral()).append('\n')
      append("  internal const val VERSION = ").append(pluginVersion.get().toKotlinLiteral()).append('\n')
      append("  internal const val DESCRIPTION = ").append(pluginDescription.get().toKotlinLiteral()).append('\n')
      append("}\n")
    }
    Files.writeString(target, source, Charsets.UTF_8)
  }
}

private fun String.toKotlinLiteral(): String = buildString(length + 2) {
  append('"')
  for (ch in this@toKotlinLiteral) {
    when (ch) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '$' -> append("\\$")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(ch)
    }
  }
  append('"')
}
