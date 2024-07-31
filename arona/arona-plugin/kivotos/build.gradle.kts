import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  kotlin("jvm") version "1.9.22"
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

val projectMainClass = "com.diyigemt.kivotos.Kivotos"
version = "0.1.16"
dependencies {
  compileOnly(project(":arona-core"))
  compileOnly(project(":arona-plugin:arona"))
  compileOnly("io.ktor:ktor-client-cio")
  compileOnly("io.ktor:ktor-client-core")
  compileOnly("io.ktor:ktor-client-content-negotiation")
  compileOnly("io.ktor:ktor-serialization-kotlinx-json-jvm")
  testImplementation(kotlin("test"))
  testImplementation("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.1")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
  testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.3")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
}
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
  }
}
tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
application {
  mainClass.set(projectMainClass)
}
tasks.withType<Jar> {
  manifest {
    attributes["Main-Class"] = projectMainClass
  }
}
tasks.withType<ShadowJar> {
  dependsOn("distTar", "distZip")
  archiveFileName.set("${project.name}-${project.version}.jar")
}
task("copyToPlugins") {
  doLast {
    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/sandbox/plugins"
    val buildJar = file(project.buildDir.path + "/libs")
      .listFiles { it -> it.isFile && it.name.contains(version.toString()) }
      ?.firstOrNull()
    if (buildJar == null) {
      logger.error("build file not found: ${project.name}")
    } else {
      // 删除旧版本插件
      file(pluginDir)
        .listFiles { it -> it.isFile && it.name.startsWith(project.name) }
        ?.forEach { it.delete() }
      buildJar.copyTo(file(pluginDir + "./" + buildJar.name), true)
      logger.error("copy ${buildJar.name} to plugin folder")
    }
  }
}
