plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.test.pressure"
  name = "pressure-test"
  author = "diyigemt"
  version = "0.1.0"
  description = "test pressure"
  mainClass = "com.diyigemt.arona.test.pressure.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
