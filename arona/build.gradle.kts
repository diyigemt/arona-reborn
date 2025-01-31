plugins {
  kotlin("jvm") version "1.9.22" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
  id("com.github.johnrengelman.shadow") version "8.1.1" apply true
}

allprojects {
  group = "com.diyigemt.arona"
  version = "1.3.6"
//  gradle.taskGraph.whenReady {
//    tasks.forEach { task ->
//      if (task.name.contains("test")) {
//        task.enabled = false
//      }
//    }
//  }
}

subprojects {
  repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
  }
}
