val kotlinVersion: String by project
val exposedVersion: String by project
plugins {
  kotlin("jvm") version "1.9.10"
  id("io.ktor.plugin") version "2.3.3"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10"
}
application {
  mainClass.set("com.diyigemt.arona.ApplicationKt")

  val isDevelopment = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
  implementation("io.ktor:ktor-server-cors")
  implementation("io.ktor:ktor-server-core-jvm")
  implementation("io.ktor:ktor-server-netty-jvm")
  implementation("io.ktor:ktor-server-status-pages")
  implementation("io.ktor:ktor-server-double-receive")
  implementation("io.ktor:ktor-server-call-logging-jvm")
  implementation("io.ktor:ktor-server-forwarded-header-jvm")
  implementation("io.ktor:ktor-server-host-common-jvm")
  implementation("io.ktor:ktor-server-status-pages-jvm")
  implementation("io.ktor:ktor-server-content-negotiation-jvm")
  implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
  implementation("io.ktor:ktor-server-double-receive-jvm")

  implementation("io.ktor:ktor-client-cio")
  implementation("io.ktor:ktor-client-core")
  implementation("io.ktor:ktor-client-websockets")
  implementation("io.ktor:ktor-client-content-negotiation")

  implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
  implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
  implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

  implementation("com.github.Towdium:PinIn:1.6.0")
  implementation("org.xerial:sqlite-jdbc:3.42.0.1")
  implementation("com.charleskorn.kaml:kaml:0.55.0")
  implementation("io.github.z4kn4fein:semver:1.4.2")
  implementation("org.reflections:reflections:0.10.2")
  implementation("com.github.ajalt.clikt:clikt:4.2.1")
  implementation("io.github.crackthecodeabhi:kreds:0.9.0")
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")
  implementation("ch.qos.logback:logback-classic:1.4.11")

  testImplementation("io.ktor:ktor-server-tests-jvm")
  testImplementation("io.ktor:ktor-server-test-host-jvm:2.3.3")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")

}
