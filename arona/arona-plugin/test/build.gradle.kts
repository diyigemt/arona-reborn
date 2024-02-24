plugins {
    kotlin("jvm") version "1.9.10"
    java
    application
}

val projectMainClass = "com.diyigemt.arona.test.PluginMain"
version = "0.0.7"
dependencies {
    compileOnly(project(":arona-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set(projectMainClass)
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = projectMainClass
    }
}

task("copyToPlugins") {
    dependsOn("distTar", "distZip")
    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/sandbox/plugins"
    val buildJar = file(project.buildDir.path + "/libs")
        .listFiles { it -> it.isFile && it.name.contains(version.toString()) }
        ?.firstOrNull()
    if (buildJar == null) {
        logger.error("build file not found: ${project.name}")
    } else {
        // 删除旧版本插件
        file(pluginDir)
            .listFiles { it -> it.isFile && it.name.startsWith(project.name) }
            ?.forEach { it.delete() }
        buildJar.copyTo(file(pluginDir + "./" + buildJar.name), true)
        logger.error("copy ${buildJar.name} to plugin folder")
    }
}
