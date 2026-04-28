plugins {
  id("arona-plugin")
  id("io.ktor.plugin") version "2.3.3"
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
  compileOnly("io.ktor:ktor-server-core-jvm")
  compileOnly("io.ktor:ktor-client-cio")
  compileOnly("io.ktor:ktor-client-core")
  compileOnly("io.ktor:ktor-client-content-negotiation")
  compileOnly("io.ktor:ktor-serialization-kotlinx-json-jvm")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.magic.PluginMain")
}
