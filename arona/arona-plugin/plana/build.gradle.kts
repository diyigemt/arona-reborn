plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.plana"
  name = "plana"
  author = "diyigemt"
  version = "0.2.0"
  description = "普拉娜踩我与图片审查"
  mainClass = "com.diyigemt.arona.plana.PluginMain"
}

dependencies {
  // 腾讯云 COS / 内容审核 SDK: host(arona-core) 不提供, 需打进插件 jar.
  implementation(libs.qcloud.cos.api)
  // Exposed 与 sqlite-jdbc 由 arona-core 以 api 暴露(编译期可见, 运行期经父 ClassLoader 提供),
  // 因此这里不再声明, 避免被 ShadowJar 重复打包与 host 冲突.
  // Ktor client 仅用于下载待审图片, 运行期由 host 提供, 编译期可见即可.
  compileOnly(libs.ktor.client.core)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.plana.PluginMain")
}
