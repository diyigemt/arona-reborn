plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.test"
  name = "hello"
  author = "diyigemt"
  version = "0.1.1"
  description = "test interaction"
  mainClass = "com.diyigemt.arona.test.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
