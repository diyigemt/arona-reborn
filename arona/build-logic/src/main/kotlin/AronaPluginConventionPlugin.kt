import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

class AronaPluginConventionPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("org.jetbrains.kotlin.jvm")
    project.pluginManager.apply("com.github.johnrengelman.shadow")

    val arona = project.extensions.create("arona", AronaPluginExtension::class.java)

    project.dependencies.add("compileOnly", project.project(":arona-core"))

    val generateBuildConfig = project.tasks.register(
      "generateAronaBuildConfig",
      GenerateAronaBuildConfig::class.java,
    ) {
      pluginId.set(arona.idProperty)
      pluginName.set(arona.nameProperty)
      pluginAuthor.set(arona.authorProperty)
      pluginVersion.set(arona.versionProperty)
      pluginDescription.set(arona.descriptionProperty)
      generatedPackage.set(arona.resolvedGeneratedPackage())
      outputDirectory.set(project.layout.buildDirectory.dir("generated/source/aronaPlugin/main"))
    }

    project.extensions.getByType(KotlinProjectExtension::class.java)
      .sourceSets.getByName("main").kotlin
      .srcDir(generateBuildConfig.flatMap { it.outputDirectory })

    project.tasks.named("compileKotlin").configure { dependsOn(generateBuildConfig) }

    project.tasks.withType(Jar::class.java).configureEach {
      val mainClassProvider = arona.mainClassProperty
      doFirst {
        manifest.attributes["Main-Class"] = mainClassProvider.get()
      }
    }

    // 普通 jar 退避到 <name>-<version>-plain.jar, 让出默认归档路径给 ShadowJar。
    // 否则 application/ktor 衍生的 distZip/distTar/startScripts 会把同路径既视作 jar 输出又视作
    // shadowJar 输出, 在 Gradle 8.2 触发 implicit dependency 校验失败。
    project.tasks.named("jar", Jar::class.java).configure {
      archiveClassifier.set("plain")
    }

    // 声明式控制 ShadowJar 命名: <baseName>-<version>.jar (无 -all 后缀)。
    // baseName 默认就是 project.name; classifier 清空; version 直接绑定到 DSL Property。
    project.tasks.withType(ShadowJar::class.java).configureEach {
      archiveClassifier.set("")
      archiveVersion.set(arona.versionProperty)
    }

    project.afterEvaluate {
      version = arona.versionProperty.get()
    }

    // io.ktor.plugin 会在自己的 afterEvaluate 中直接 set ShadowJar.archiveFileName 字面值,
    // 覆盖 archive* 三要素的 convention 计算结果。仅在它确实 apply 时启用一次最终覆盖,
    // 时机用 Gradle 标准 hook gradle.projectsEvaluated (晚于所有 afterEvaluate)。
    project.pluginManager.withPlugin("io.ktor.plugin") {
      project.gradle.projectsEvaluated {
        project.tasks.named("shadowJar", ShadowJar::class.java).configure {
          archiveFileName.set("${project.name}-${project.version}.jar")
        }
      }
    }

    val shadowJar = project.tasks.named("shadowJar", ShadowJar::class.java)
    project.tasks.register("copyToPlugins") {
      group = "arona"
      description = "Copy the shadow jar into arona-core/sandbox/plugins, replacing prior versions."
      dependsOn(shadowJar)
      doLast {
        val pluginDir = project.rootProject.project(":arona-core")
          .projectDir.resolve("sandbox").resolve("plugins")
        pluginDir.mkdirs()
        val jar = shadowJar.get().archiveFile.get().asFile
        if (!jar.isFile) {
          logger.error("build file not found: ${project.name}")
          return@doLast
        }
        pluginDir.listFiles { f -> f.isFile && f.name.startsWith(project.name) }
          ?.forEach { it.delete() }
        jar.copyTo(pluginDir.resolve(jar.name), overwrite = true)
        logger.lifecycle("copy ${jar.name} to plugin folder")
      }
    }
  }
}
