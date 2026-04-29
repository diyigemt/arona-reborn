plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.shadow) apply true
}

allprojects {
  group = "com.diyigemt.arona"
  version = "1.3.6"
  buildscript {
    configurations.classpath {
      resolutionStrategy.capabilitiesResolution.withCapability("gradle.plugin.com.github.johnrengelman:shadow") {
        selectHighestVersion()
      }
    }
  }
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

// 开发常用插件集合: 仅包含日常调试/部署需要落到 sandbox/plugins 的核心插件。
// 增减插件只需修改此列表, 聚合 task 的 description 与 dependsOn 会自动同步。
val devPlugins = listOf(
  "kivotos",
  "debugger",
  "custom-menu",
  "content-audit",
  "chat-command",
  "arona",
)

tasks.register("copyDevPlugins") {
  group = "arona"
  description = "复制开发常用插件 (${devPlugins.joinToString(", ")}) 的 shadow jar 到 arona-core/sandbox/plugins"
  dependsOn(devPlugins.map { ":arona-plugin:$it:copyToPlugins" })
}
