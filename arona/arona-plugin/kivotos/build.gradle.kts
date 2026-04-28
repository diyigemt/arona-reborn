plugins {
  id("arona-plugin")
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

arona {
  id = "com.diyigemt.kivotos"
  name = "kivotos"
  author = "diyigemt"
  version = "0.1.16"
  description = "hello world"
  mainClass = "com.diyigemt.kivotos.Kivotos"
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
  }
}

dependencies {
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

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.kivotos.Kivotos")
}
