plugins {
  id("arona-plugin")
  // index.json 用 kotlinx.serialization 解析, 需要序列化编译插件生成 @Serializable 代码。
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.rollpig"
  name = "rollpig"
  author = "diyigemt"
  version = "0.3.0"
  description = "今日小猪"
  mainClass = "com.diyigemt.arona.rollpig.PluginMain"
}

kotlin {
  compilerOptions {
    // Markdown DSL 的无参 at() 是 context(sender: UserCommandSender) 形式, 需此选项方可调用(同 arona 模块)。
    freeCompilerArgs.add("-Xcontext-parameters")
  }
}

dependencies {
  // kotlinx-serialization-json 与 Ktor client 均由 arona-core 以 implementation/bundle 持有(运行期经父
  // ClassLoader 可见), 编译期不会传递到本模块, 故这里 compileOnly 声明, 既能编译又不会被 ShadowJar 打进插件 jar。
  // Exposed 与 sqlite-jdbc 由 arona-core 以 api 暴露, 编译期已可见, 无需声明。
  // 「随机小猪」运行期需向 pighub.top 拉取图片列表(其余指令不联网), 故引入 ktor client 编译期依赖。
  compileOnly(libs.kotlinx.serialization.json)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.cio)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
