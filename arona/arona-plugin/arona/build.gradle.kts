plugins {
  id("arona-plugin")
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}

arona {
  id = "com.diyigemt.arona"
  name = "arona"
  author = "diyigemt"
  version = "1.3.19"
  description = "arona plugin"
  mainClass = "com.diyigemt.arona.arona.Arona"
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
  }
}

dependencies {
  compileOnly(libs.ktor.server.core.jvm)
  compileOnly(libs.ktor.client.cio)
  compileOnly(libs.ktor.client.core)
  compileOnly(libs.ktor.client.content.negotiation)
  compileOnly(libs.ktor.serialization.kotlinx.json.jvm)
  api(libs.skiko.awt.runtime.linux.x64)
  api(libs.skiko.awt.runtime.windows.x64)
  api(libs.thumbnailator)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.arona.Arona")
}
