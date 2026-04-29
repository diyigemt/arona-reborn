plugins {
  id("arona-plugin")
  id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
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
