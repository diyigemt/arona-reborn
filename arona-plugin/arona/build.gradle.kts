plugins {
    kotlin("jvm") version "1.9.10"
    id("io.ktor.plugin") version "2.3.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.10"
}

val projectMainClass = "com.diyigemt.arona.arona.Arona"
version = "0.1.0"
dependencies {
    compileOnly(project(":arona-core"))

    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")

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

//task("copyToPlugins") {
//    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/plugins"
//    val buildJar = file(project.buildDir.path + "/libs")
//        .listFiles { it -> it.isFile && it.name.contains(version.toString()) }
//        ?.firstOrNull()
//    if (buildJar == null) {
//        logger.error("build file not found: ${project.name}")
//    } else {
//        // 删除旧版本插件
//        file(pluginDir)
//            .listFiles { it -> it.isFile && it.name.startsWith(project.name) }
//            ?.forEach { it.delete() }
//        buildJar.copyTo(file(pluginDir + "./" + buildJar.name), true)
//        logger.error("copy ${buildJar.name} to plugin folder")
//    }
//}
