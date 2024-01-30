import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  kotlin("jvm") version "1.9.10"
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10"
}

val projectMainClass = "com.diyigemt.arona.arona.Arona"
version = "1.0.17"
dependencies {
  compileOnly(project(":arona-core"))

  implementation("io.ktor:ktor-client-cio")
  implementation("io.ktor:ktor-client-core")
  implementation("io.ktor:ktor-client-content-negotiation")
  implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
  implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.9")
  implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.7.9")
  implementation("net.coobird:thumbnailator:0.4.20")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
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
  dependsOn("distTar", "distZip")
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
