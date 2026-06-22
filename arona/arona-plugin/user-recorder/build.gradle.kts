plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.user.recorder"
  name = "user-recorder"
  author = "diyigemt"
  version = "1.2.12"
  description = "record user data"
  mainClass = "com.diyigemt.arona.user.recorder.PluginMain"
}

dependencies {
  // MongoDB coroutine driver 与 org.bson 由 arona-core 以 api 暴露, 经 compileOnly(project(":arona-core")) 即可见。
  // 归档文档使用 org.bson.Document + 默认 codec, 不引入 bson-kotlinx / kotlin-serialization 插件。
  testImplementation(kotlin("test"))
  // 测试源集不继承 main 的 compileOnly(project(":arona-core")), 单测纯逻辑需显式引入 kotlinx-datetime。
  testImplementation(libs.kotlinx.datetime)
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
