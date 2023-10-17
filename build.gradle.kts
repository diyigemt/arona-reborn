import java.net.URI

allprojects {
  group = "com.diyigemt"
  version = "0.0.1"

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
    maven {
      url = URI("https://jitpack.io")
    }
  }
}
