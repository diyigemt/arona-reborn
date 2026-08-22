plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.chatbot"
  name = "chatbot"
  author = "diyigemt"
  version = "0.1.0"
  description = "群聊被动 AI 闲聊"
  mainClass = "com.diyigemt.arona.chatbot.PluginMain"
}

dependencies {
  // kotlinx-serialization-json / Ktor client / bson-kotlinx 均由 arona-core 以 implementation 持有 (运行期经父
  // ClassLoader 可见), 编译期不传递到本模块, 故 compileOnly 声明: 能编译, 且不会被 ShadowJar 打进插件 jar.
  compileOnly(libs.kotlinx.serialization.json)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.cio)
  testImplementation(kotlin("test"))
  // 测试源集不继承 main 的 compileOnly(project(":arona-core")) (约定插件只对 main 注入), 显式引入.
  testImplementation(project(":arona-core"))
  testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.chatbot.PluginMain")
}
