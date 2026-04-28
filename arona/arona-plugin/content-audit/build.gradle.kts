plugins {
  id("arona-plugin")
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22"
}

arona {
  id = "com.diyigemt.arona.content.audit"
  name = "content-audit"
  author = "diyigemt"
  version = "0.1.3"
  description = "内容审核"
  mainClass = "com.diyigemt.arona.content.audit.PluginMain"
}

dependencies {
  implementation("com.qcloud:cos_api:5.6.187")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.content.audit.PluginMain")
}
