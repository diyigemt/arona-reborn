import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val kotlinVersion: String by project
val exposedVersion: String by project
plugins {
  kotlin("jvm") version "2.0.21"
  id("io.ktor.plugin") version "2.3.13"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
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

  // kts
  api("org.jetbrains.kotlin:kotlin-main-kts")
  api("org.jetbrains.kotlin:kotlin-scripting-jvm")
  api("org.jetbrains.kotlin:kotlin-scripting-common")
  api("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
  api("org.jetbrains.kotlin:kotlin-scripting-dependencies")
  api("org.apache.ivy:ivy:2.5.2")
  
  implementation("io.ktor:ktor-server-cors")
  implementation("io.ktor:ktor-server-core-jvm")
  implementation("io.ktor:ktor-server-netty-jvm")
  implementation("io.ktor:ktor-server-status-pages")
  implementation("io.ktor:ktor-server-double-receive")
  implementation("io.ktor:ktor-server-call-logging-jvm")
  implementation("io.ktor:ktor-server-forwarded-header-jvm")
  implementation("io.ktor:ktor-server-host-common-jvm")
  implementation("io.ktor:ktor-server-status-pages-jvm")
  implementation("io.ktor:ktor-server-double-receive-jvm")
  implementation("io.ktor:ktor-server-content-negotiation-jvm")

  implementation("io.ktor:ktor-client-cio")
  implementation("io.ktor:ktor-client-core")
  implementation("io.ktor:ktor-client-websockets")
  implementation("io.ktor:ktor-client-content-negotiation")

  implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")

  api("org.jetbrains.exposed:exposed-dao:$exposedVersion")
  api("org.jetbrains.exposed:exposed-core:$exposedVersion")
  api("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
  api("org.jetbrains.exposed:exposed-json:$exposedVersion")
  api("org.mongodb:mongodb-driver-kotlin-coroutine:4.11.1")
  // bson-kotlinx 提供 KotlinSerializerCodecProvider, 仅 host 自身构造 Mongo codec registry 时需要,
  // 不必传递给下游 plugin 的编译 classpath, 故走 implementation.
  implementation("org.mongodb:bson-kotlinx:4.11.1")

  api("io.github.crackthecodeabhi:kreds:0.9.1")

  implementation("org.jline:jline:3.25.0")
  implementation("com.github.Towdium:PinIn:1.6.0")
  implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
  implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
  implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
  //ABAC
  api(fileTree("lib"))

  api("org.mariadb.jdbc:mariadb-java-client:3.3.3")
  api("org.xerial:sqlite-jdbc:3.42.0.1")
  api("com.charleskorn.kaml:kaml:0.55.0")
  api("io.github.z4kn4fein:semver:1.4.2")
  api("org.reflections:reflections:0.10.2")
  api("com.github.ajalt.clikt:clikt:4.2.1")
  api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")

  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
  api("ch.qos.logback:logback-core:1.5.12")
  api("ch.qos.logback:logback-classic:1.5.12")

  testImplementation("io.ktor:ktor-server-tests-jvm")
  testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.13")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}

tasks.withType<Jar> {
  manifest.attributes["Main-Class"] = "com.diyigemt.arona.ApplicationKt"
  doFirst {
    manifest.attributes["Class-Path"] = configurations.runtimeClasspath.get().files.toList().joinToString(" ") { "lib/${it.name}" }
  }
  duplicatesStrategy = DuplicatesStrategy.WARN
}
