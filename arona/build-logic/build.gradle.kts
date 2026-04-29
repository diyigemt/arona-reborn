plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
  implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:8.3.5")
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
