plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
  implementation("com.github.johnrengelman.shadow:com.github.johnrengelman.shadow.gradle.plugin:8.1.1")
}

gradlePlugin {
  plugins {
    create("aronaPlugin") {
      id = "arona-plugin"
      implementationClass = "AronaPluginConventionPlugin"
      displayName = "Arona Plugin Convention"
      description = "Convention plugin: single-source plugin metadata via DSL + generated BuildConfig"
    }
  }
}
