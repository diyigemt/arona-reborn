import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val kotlinVersion: String by project
val exposedVersion: String by project
plugins {
  kotlin("jvm") version "1.9.22"
  id("io.ktor.plugin") version "2.3.7"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
  }
}
version = "1.2.32"
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
}
tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.WARN
}
task<Copy>("copyDep") {
  val workingDir = projectDir.resolve("sandbox")
  duplicatesStrategy = DuplicatesStrategy.WARN
  configurations.default.configure {
    isCanBeResolved = true
  }
  into("$workingDir/lib")
  from(configurations.default)
  from("lib")
}
//tasks.withType<ShadowJar> {
//  dependsOn("distTar", "distZip")
//  archiveFileName.set("${project.name}-${project.version}.jar")
//}
task("copyToPlugins") {
  doLast {
    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/sandbox"
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

  api("io.github.crackthecodeabhi:kreds:0.9.1")

  implementation("org.jline:jline:3.25.0")
  implementation("com.github.Towdium:PinIn:1.6.0")
  implementation("org.jetbrains.kotlinx:atomicfu:0.22.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf")
  //ABAC
  api(fileTree("lib"))

  api("org.mariadb.jdbc:mariadb-java-client:3.3.3")
  api("org.xerial:sqlite-jdbc:3.42.0.1")
  api("com.charleskorn.kaml:kaml:0.55.0")
  api("io.github.z4kn4fein:semver:1.4.2")
  api("org.reflections:reflections:0.10.2")
  api("com.github.ajalt.clikt:clikt:4.2.1")
  api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")

  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
  api("ch.qos.logback:logback-core:1.4.12")
  api("ch.qos.logback:logback-classic:1.4.14")

  testImplementation("io.ktor:ktor-server-tests-jvm")
  testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.3")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}

tasks.withType<Jar> {
  configurations.default.configure {
    isCanBeResolved = true
  }
  manifest.attributes["Main-Class"] = "com.diyigemt.arona.ApplicationKt"
  manifest.attributes["Class-Path"] = configurations.default.get().files.toList().joinToString(" ") { "lib/${it.name}" }
  duplicatesStrategy = DuplicatesStrategy.WARN
}
