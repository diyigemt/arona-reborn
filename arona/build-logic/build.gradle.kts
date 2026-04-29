plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.shadow.gradle.plugin)
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
