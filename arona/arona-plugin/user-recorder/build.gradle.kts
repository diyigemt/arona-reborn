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
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
