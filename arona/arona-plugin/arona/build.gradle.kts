plugins {
  id("arona-plugin")
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
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
  compileOnly("io.ktor:ktor-server-core-jvm")
  compileOnly("io.ktor:ktor-client-cio")
  compileOnly("io.ktor:ktor-client-core")
  compileOnly("io.ktor:ktor-client-content-negotiation")
  compileOnly("io.ktor:ktor-serialization-kotlinx-json-jvm")
  api("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.7.9")
  api("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.7.9")
  api("net.coobird:thumbnailator:0.4.20")
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()
  workingDir = rootProject.project("arona-core").projectDir.resolve("sandbox")
}

application {
  mainClass.set("com.diyigemt.arona.arona.Arona")
}
