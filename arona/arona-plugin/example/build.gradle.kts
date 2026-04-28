plugins {
  id("arona-plugin")
  id("io.ktor.plugin") version "2.3.3"
}

arona {
  id = "com.diyigemt.arona.example"
  name = "example"
  author = "diyigemt"
  version = "2.3.3"
  description = "hello world"
  mainClass = "com.diyigemt.arona.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.PluginMain")
}
