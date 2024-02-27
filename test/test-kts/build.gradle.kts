plugins {
    kotlin("jvm") version "1.8.21"
}

group = "com.diyigemt"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-main-kts:1.8.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:1.8.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:1.8.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:1.8.21")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:1.8.21")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
