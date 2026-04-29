plugins {
  id("arona-plugin")
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona.custom.menu"
  name = "custom-menu"
  author = "diyigemt"
  version = "0.4.0"
  description = "快捷菜单"
  mainClass = "com.diyigemt.arona.custom.menu.PluginMain"
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}
