plugins {
  id("arona-plugin")
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.chat.command"
  name = "chat-command"
  author = "diyigemt"
  version = "0.1.7"
  description = "chat-command"
  mainClass = "com.diyigemt.arona.chat.command.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
