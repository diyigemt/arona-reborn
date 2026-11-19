plugins {
  id("arona-plugin")
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.test"
  name = "hello"
  author = "diyigemt"
  version = "0.2.0"
  description = "test interaction"
  mainClass = "com.diyigemt.arona.test.PluginMain"
}

dependencies {
  // Ktor server 与 kotlinx-serialization 由 arona-core 以 implementation 持有 (运行期经父 ClassLoader 可见), 编译期不传递, 故 compileOnly.
  compileOnly(libs.ktor.server.core.jvm)
  compileOnly(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
  testImplementation(project(":arona-core"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
