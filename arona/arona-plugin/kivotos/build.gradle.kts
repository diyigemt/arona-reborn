plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.kivotos"
  name = "kivotos"
  author = "diyigemt"
  version = "0.1.17"
  description = "hello world"
  mainClass = "com.diyigemt.kivotos.Kivotos"
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
    // kivotos 跨多个文件用 kotlin.time.Clock / Instant (kotlinx-datetime 0.7.x 后迁回 stdlib),
    // 模块级 opt-in 避免在每个使用文件各自 @file:OptIn.
    optIn.add("kotlin.time.ExperimentalTime")
  }
}

dependencies {
  compileOnly(project(":arona-plugin:arona"))
  compileOnly(libs.ktor.client.cio)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.content.negotiation)
  compileOnly(libs.ktor.serialization.kotlinx.json.jvm)

  // bson-kotlinx 提供 KotlinSerializerCodecProvider; host (arona-core) 已以 implementation 持有,
  // 运行期经父 ClassLoader 可见, 此处仅需编译期可见。compileOnly 还能避免它 POM 里固定的
  // kotlin-reflect/stdlib-jdk8 1.8.10 进入 runtimeClasspath 触发 syncPluginLibraries 版本告警。
  compileOnly(libs.mongodb.bson.kotlinx)

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
