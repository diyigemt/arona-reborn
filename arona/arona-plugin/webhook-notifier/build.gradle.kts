plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.webhook.notifier"
  name = "webhook-notifier"
  author = "diyigemt"
  version = "0.1.2"
  description = "接收 webhook 并转发到固定群"
  mainClass = "com.diyigemt.arona.webhook.notifier.PluginMain"
}

dependencies {
  // Ktor server 和 kotlinx-serialization-json 由 arona-core 以 implementation 持有 (运行期经父 ClassLoader 可见), 编译期不传递到本模块, 故 compileOnly 声明.
  compileOnly(libs.ktor.server.core.jvm)
  compileOnly(libs.ktor.server.netty.jvm)
  compileOnly(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
  // 测试源集不继承 main 的 compileOnly(project(":arona-core")) (约定插件只对 main 注入), 显式引入.
  testImplementation(project(":arona-core"))
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.server.netty.jvm)
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
