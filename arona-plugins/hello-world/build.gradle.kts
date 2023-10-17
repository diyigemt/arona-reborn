plugins {
    kotlin("jvm") version "1.9.10"
    java
    application
}

group = "com.diyigemt.arona"
version = "1.0-SNAPSHOT"
val projectMainClass = "com.diyigemt.arona.hello.PluginMain"

dependencies {
    implementation(project(":arona-core"))
    implementation("org.slf4j:slf4j-api:2.0.7")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set(projectMainClass)
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = projectMainClass
    }
}
