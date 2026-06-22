plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona"
  name = "arona"
  author = "diyigemt"
  version = "1.4.0"
  description = "arona plugin"
  mainClass = "com.diyigemt.arona.arona.Arona"
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
  }
}

dependencies {
  compileOnly(libs.ktor.server.core.jvm)
  compileOnly(libs.ktor.client.cio)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.content.negotiation)
  compileOnly(libs.ktor.serialization.kotlinx.json.jvm)
  // host (arona-core) 以 implementation 引入 caffeine, 运行期经父 ClassLoader 提供; 此处仅需编译期可见.
  compileOnly(libs.caffeine)
  api(libs.skiko.awt.runtime.linux.x64)
  api(libs.skiko.awt.runtime.windows.x64)
  api(libs.thumbnailator)
  testImplementation(kotlin("test"))
  // 测试源集不继承 main 的 compileOnly(project(":arona-core")) (约定插件只对 main 注入), 单测需直接构造
  // 消息链/异常等 core 类型, 故显式以 testImplementation 引入 arona-core。
  testImplementation(project(":arona-core"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.arona.Arona")
}
