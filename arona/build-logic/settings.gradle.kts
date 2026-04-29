// build-logic 是 includeBuild 引入的复合构建; Gradle 不会自动把主构建的 version catalog
// 透传过来, 必须显式从同一份 toml 加载, 才能在 build-logic 自身的 dependencies {}
// 中用 libs.* 访问器 (见 build-logic/build.gradle.kts).
dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}

rootProject.name = "build-logic"
