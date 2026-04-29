plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
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
  compileOnly(libs.ktor.client.cio)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.content.negotiation)
  compileOnly(libs.ktor.serialization.kotlinx.json.jvm)

  // bson-kotlinx 提供 KotlinSerializerCodecProvider, host (arona-core) 未引入此模块。
  // implementation 让 shadowJar 把 org.bson.codecs.kotlinx.* 打进 kivotos 插件 jar,
  // 通过 PluginManager 的 parent-first URLClassLoader 让 kivotos 单独可见, 不污染 arona-core。
  // 当前仅"运行时可见", 实际接入 codecRegistry 在后续步骤完成。
  implementation(libs.mongodb.bson.kotlinx)

  testImplementation(kotlin("test"))
  testImplementation(libs.mongodb.driver.kotlin.coroutine)
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlinx.serialization.core)
  testImplementation(libs.kotlinx.coroutines.core.jvm)
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.kivotos.Kivotos")
}
