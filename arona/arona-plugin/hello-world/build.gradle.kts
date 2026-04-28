plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.hello"
  name = "hello"
  author = "diyigemt"
  version = "2.3.3"
  description = "hello world"
  mainClass = "com.diyigemt.arona.hello.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
