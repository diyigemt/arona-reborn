plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
}

arona {
  id = "com.diyigemt.arona.magic"
  name = "magic"
  author = "diyigemt"
  version = "0.0.1"
  description = "hello magic"
  mainClass = "com.diyigemt.arona.magic.PluginMain"
}

dependencies {
  compileOnly(libs.ktor.server.core.jvm)
  compileOnly(libs.ktor.client.cio)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.content.negotiation)
  compileOnly(libs.ktor.serialization.kotlinx.json.jvm)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.magic.PluginMain")
}
