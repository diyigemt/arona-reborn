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

  // bson-kotlinx 提供 KotlinSerializerCodecProvider, host (arona-core) 未引入此模块。
  // implementation 让 shadowJar 把 org.bson.codecs.kotlinx.* 打进 kivotos 插件 jar,
  // 通过 PluginManager 的 parent-first URLClassLoader 让 kivotos 单独可见, 不污染 arona-core。
  // 当前仅"运行时可见", 实际接入 codecRegistry 在后续步骤完成。
  implementation("org.mongodb:bson-kotlinx:4.11.1")

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
