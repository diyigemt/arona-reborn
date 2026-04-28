plugins {
  id("arona-plugin")
}

arona {
  id = "com.diyigemt.arona.maintain.notifier"
  name = "maintain-notifier"
  author = "diyigemt"
  version = "0.1.1"
  description = "维护通知器"
  mainClass = "com.diyigemt.arona.maintain.notifier.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
