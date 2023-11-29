plugins {
  kotlin("jvm") version "1.9.10" apply false
  id("com.github.johnrengelman.shadow") version "8.1.1" apply true
}

allprojects {
  group = "com.diyigemt.arona"
  version = "0.1.12"
  gradle.taskGraph.whenReady {
    tasks.forEach { task ->
      if (task.name.contains("test")) {
        task.enabled = false
      }
    }
  }
}

subprojects {
  repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
  }
}
