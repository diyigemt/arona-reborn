plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.shadow) apply true
}

allprojects {
  group = "com.diyigemt.arona"
  version = "2.0.0"
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
  "plana",
)

// syncPluginLibraries 的条目模型; 放脚本顶层是因为 Kotlin DSL 在 doLast lambda 内
// 声明局部 data class 会触发脚本编译器 codegen 异常。
data class PluginLib(val ga: String, val version: String, val file: File, val plugin: String)

// 静态共享库同步: 插件 jar 是薄包 (shadow 不再合并依赖), 各插件的外部运行时依赖统一
// 落到 arona-core/sandbox/libraries, core 启动时与 plugins/ 一起装入同一 URLClassLoader。
// core runtimeClasspath 已有的 GA 一律不进共享库: 扁平 ClassLoader parent-first, core 的
// 版本必然胜出, 再同步只是死重; 插件声明了不同版本时告警提醒对齐 core。
tasks.register("syncPluginLibraries") {
  group = "arona"
  description = "同步所有插件的非 core 运行时依赖到 arona-core/sandbox/libraries"
  doLast {
    val core = project(":arona-core")
    val coreVersions = core.configurations.getByName("runtimeClasspath")
      .resolvedConfiguration.resolvedArtifacts
      .groupBy({ "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }, { it.moduleVersion.id.version })

    val libs = mutableListOf<PluginLib>()
    subprojects
      .filter { it.path.startsWith(":arona-plugin:") && it.extensions.findByName("arona") != null }
      .sortedBy { it.path }
      .forEach { plugin ->
        plugin.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
          .forEach { artifact ->
            if (artifact.id.componentIdentifier is ProjectComponentIdentifier) {
              throw GradleException("插件 ${plugin.path} 的 runtimeClasspath 含 project 依赖, 静态共享库模式不支持")
            }
            if (artifact.file.extension != "jar") {
              throw GradleException("插件 ${plugin.path} 的依赖 ${artifact.file.name} 不是 jar, 静态共享库模式不支持")
            }
            val id = artifact.moduleVersion.id
            val ga = "${id.group}:${id.name}"
            val provided = coreVersions[ga]
            when {
              provided == null -> libs += PluginLib(ga, id.version, artifact.file, plugin.path)
              id.version !in provided -> logger.warn(
                "插件 ${plugin.path} 依赖 $ga:${id.version}, 但 core 提供 ${provided.distinct().joinToString("/")}, parent-first 下以 core 为准"
              )
            }
          }
      }

    // 跨插件同 GA 不同版本: 扁平 ClassLoader 里无法共存, 直接失败并点名
    libs.groupBy { it.ga }
      .filterValues { g -> g.map { it.version }.distinct().size > 1 }
      .takeIf { it.isNotEmpty() }
      ?.let { conflicts ->
        throw GradleException(conflicts.entries.joinToString("\n", prefix = "插件共享库版本冲突:\n") { (ga, list) ->
          list.groupBy({ it.version }, { it.plugin }).entries
            .joinToString("\n") { (v, plugins) -> "  $ga:$v <- ${plugins.distinct().joinToString(", ")}" }
        })
      }
    // 不同 GA 撞出同名 jar: 目录按文件名寻址, 无法共存
    libs.groupBy { it.file.name }
      .filterValues { g -> g.map { it.ga }.distinct().size > 1 }
      .takeIf { it.isNotEmpty() }
      ?.let { conflicts ->
        throw GradleException(conflicts.entries.joinToString("\n", prefix = "插件共享库文件名冲突:\n") { (name, list) ->
          "  $name <- ${list.map { it.ga }.distinct().joinToString(", ")}"
        })
      }

    // 经上两道检查后文件名与 GAV 一一对应 (classifier 已含在文件名里), 按文件名去重/增量/清理
    val dir = core.projectDir.resolve("sandbox").resolve("libraries").apply { mkdirs() }
    val wanted = libs.associateBy { it.file.name }
    wanted.values.sortedBy { it.file.name }.forEach { lib ->
      val target = dir.resolve(lib.file.name)
      // release 坐标不可变, 同名同长即视为同内容; 长度不符说明上次复制被打断, 重拷
      if (!target.isFile || target.length() != lib.file.length()) {
        lib.file.copyTo(target, overwrite = true)
        logger.lifecycle("copy library ${lib.file.name} (${lib.ga}:${lib.version})")
      }
    }
    dir.listFiles { f -> f.isFile && f.extension == "jar" }
      ?.filterNot { it.name in wanted }
      ?.forEach {
        if (!it.delete()) {
          throw GradleException("无法删除陈旧共享库 $it, 残留 jar 会继续进入 ClassLoader")
        }
        logger.lifecycle("delete stale library ${it.name}")
      }
  }
}

tasks.register("copyDevPlugins") {
  group = "arona"
  description = "复制开发常用插件 (${devPlugins.joinToString(", ")}) 的 shadow jar 到 arona-core/sandbox/plugins"
  dependsOn(":arona-core:copyToPlugins")
  dependsOn(devPlugins.map { ":arona-plugin:$it:copyToPlugins" })
}
