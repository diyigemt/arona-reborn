plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.debugger"
  name = "debugger"
  author = "diyigemt"
  version = "0.2.1"
  description = "debugger"
  mainClass = "com.diyigemt.arona.debugger.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
