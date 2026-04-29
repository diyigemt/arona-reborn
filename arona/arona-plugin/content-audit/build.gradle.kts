plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
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
  implementation(libs.qcloud.cos.api)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.content.audit.PluginMain")
}
