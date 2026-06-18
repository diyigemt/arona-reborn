plugins {
  id("arona-plugin")
  // index.json 用 kotlinx.serialization 解析, 需要序列化编译插件生成 @Serializable 代码。
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.rollpig"
  name = "rollpig"
  author = "diyigemt"
  version = "0.2.0"
  description = "今日小猪"
  mainClass = "com.diyigemt.arona.rollpig.PluginMain"
}

dependencies {
  // kotlinx-serialization-json 由 arona-core 以 implementation 持有(运行期经父 ClassLoader 可见),
  // 编译期不会传递到本模块, 故这里 compileOnly 声明, 既能编译又不会被 ShadowJar 打进插件 jar。
  // Exposed 与 sqlite-jdbc 由 arona-core 以 api 暴露, 编译期已可见, 无需声明。
  // 本插件不发起任何网络请求, 不依赖 ktor。
  compileOnly(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
