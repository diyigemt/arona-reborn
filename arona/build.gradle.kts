import java.net.URI

plugins {
  kotlin("jvm") version "1.9.10" apply false
}

allprojects {
  group = "com.diyigemt.arona"
  version = "0.0.1"

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
    maven {
      url = URI("https://jitpack.io")
    }
  }
}
