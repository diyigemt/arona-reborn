import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
  }
  jvmToolchain(17)
}
version = "1.3.7"
application {
  mainClass = "com.diyigemt.arona.ApplicationKt"

  val isDevelopment = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.withType<ShadowJar> {
  isZip64 = true
}
tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.WARN
}
tasks.withType<Test> {
  workingDir = projectDir.resolve("sandbox")
  doFirst {
    workingDir.mkdirs()
    val configFile = workingDir.resolve("config.yaml")
    if (!configFile.exists()) {
      configFile.writeText(
        projectDir.resolve("config_template.yaml").readText(Charsets.UTF_8),
        Charsets.UTF_8,
      )
    }
  }
}
tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.WARN
}
tasks.register<Copy>("copyDep") {
  val workingDir = projectDir.resolve("sandbox")
  duplicatesStrategy = DuplicatesStrategy.WARN
  into("$workingDir/lib")
  from(configurations.runtimeClasspath)
  from("lib")
}
//tasks.withType<ShadowJar> {
//  dependsOn("distTar", "distZip")
//  archiveFileName.set("${project.name}-${project.version}.jar")
//}
tasks.register("copyToPlugins") {
  doLast {
    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/sandbox"
    val buildJar = project.layout.buildDirectory.dir("libs").get().asFile
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
      logger.error("copy ${buildJar.name} to sanbox folder")
    }
  }
}

dependencies {
  // kts: kotlin scripting + Ivy 协同支撑 .kts 脚本依赖解析
  api(libs.bundles.kotlin.scripting.base)
  api(libs.ivy)

  implementation(libs.bundles.ktor.server.base)
  implementation(libs.bundles.ktor.client.base)
  implementation(libs.ktor.serialization.kotlinx.json.jvm)

  api(libs.bundles.exposed.base)
  api(libs.mongodb.driver.kotlin.coroutine)
  // bson-kotlinx 提供 KotlinSerializerCodecProvider, 仅 host 自身构造 Mongo codec registry 时需要,
  // 不必传递给下游 plugin 的编译 classpath, 故走 implementation.
  implementation(libs.mongodb.bson.kotlinx)

  api(libs.kreds)

  implementation(libs.jline)
  implementation(libs.pinin)
  implementation(libs.caffeine)
  implementation(libs.atomicfu)
  implementation(libs.bcpkix.jdk18on)
  implementation(libs.bundles.kotlinx.serialization.base)
  api(libs.mariadb.java.client)
  api(libs.sqlite.jdbc)
  api(libs.kaml)
  api(libs.semver)
  api(libs.reflections)
  api(libs.clikt)
  // P2-C2: Mordant 与 Jansi 由本地 lib/ jar 升级到 Maven 坐标; Clikt 5 transitively 拉 Mordant 3,
  // 这里显式声明是因为项目代码层 (CommandMain / KeepInputConsole) 直接 import Mordant API.
  api(libs.mordant)
  implementation(libs.jansi)
  api(libs.kotlinx.datetime)

  api(libs.bundles.kotlinx.coroutines.base)
  api(libs.bundles.logback.base)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(kotlin("test-junit"))
}

tasks.withType<Jar> {
  manifest.attributes["Main-Class"] = "com.diyigemt.arona.ApplicationKt"
  doFirst {
    manifest.attributes["Class-Path"] = configurations.runtimeClasspath.get().files.toList().joinToString(" ") { "lib/${it.name}" }
  }
  duplicatesStrategy = DuplicatesStrategy.WARN
}
