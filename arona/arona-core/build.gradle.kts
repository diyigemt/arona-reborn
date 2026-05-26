import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktor)
  alias(libs.plugins.kotlin.serialization)
}
kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-parameters")
  }
  jvmToolchain(17)
}
version = "2.0.0"
application {
  mainClass = "com.diyigemt.arona.ApplicationKt"

  val isDevelopment = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.withType<ShadowJar> {
  isZip64 = true
}
tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.WARN
}
tasks.withType<Test> {
  workingDir = projectDir.resolve("sandbox")
  doFirst {
    workingDir.mkdirs()
    val configFile = workingDir.resolve("config.yaml")
    if (!configFile.exists()) {
      configFile.writeText(
        projectDir.resolve("config_template.yaml").readText(Charsets.UTF_8),
        Charsets.UTF_8,
      )
    }
  }
}
tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.WARN
}

// 把 kotlin-main-kts fat jar 内未 relocate 的 SLF4J 1.x 类剥离, 解决从 lib/ 启动时
// org.slf4j.LoggerFactory 被错误版本接管导致 Logback CustomAppender 永不触发, 进而
// 撕碎 JLine prompt / 已输入字符的问题.
//
// 背景: kotlin-main-kts-2.3.20.jar 在原始 org/slf4j/* 包路径下打入了一份 SLF4J 1.x
// (含 LoggerFactory / impl/StaticLoggerBinder / impl/SimpleLoggerFactory 等), 与项目主依赖
// slf4j-api-2.0.17.jar (SLF4J 2.x) 在同一 FQCN 下出现两套不同版本的同名接口/类. 主 jar
// manifest Class-Path 把 kotlin-main-kts 排在 slf4j-api 之前, JVM 第一匹配命中即用, 加载到
// 1.x LoggerFactory; 它通过 StaticLoggerBinder.getSingleton() 解析到 kts 内置的
// SimpleLoggerFactory, 全部日志走 SimpleLogger 直写 System.err, 完全绕过 Logback ->
// CustomAppender#append 永远不被调用 -> lineReader.printAbove 永远不被调用 -> 后台日志
// raw 写 stdout/stderr, 覆盖当前输入行无法恢复. IDEA 直跑可能因 classpath 顺序不同偶然命中
// 2.x LoggerFactory 走对路径, 不能视为没问题.
//
// 选 jar surgery 而非重排 Class-Path: 顺序修复只是 workaround, 重复定义留在 classpath 上,
// 一旦 Gradle / IDE / 插件引入顺序变化就会复发. 这里物理移除重复条目, 从根上消除歧义.
// 选 Zip 任务而非 Jar 任务: kotlin-main-kts 自带 MANIFEST.MF (含 Implementation-Title /
// Multi-Release 等), Jar 任务会注入 Gradle 自己的 manifest 并覆盖原值, Zip 任务原样保留.
val patchedKotlinMainKts by tasks.registering(Zip::class) {
  group = "build"
  description = "Repackage kotlin-main-kts to drop bundled, unrelocated SLF4J classes."

  val originalJar = configurations.runtimeClasspath.map { cp ->
    cp.files.single { it.name.startsWith("kotlin-main-kts-") && it.name.endsWith(".jar") }
  }

  archiveFileName.set(originalJar.map { it.name })
  destinationDirectory.set(layout.buildDirectory.dir("patched-libs"))
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE

  from(originalJar.map { zipTree(it) }) {
    // 仅清 SLF4J 痕迹 + 签名块, 保留 javax.script.ScriptEngineFactory / javax.annotation.processing.Processor /
    // org.apache.commons.logging.LogFactory 三个 SPI (kts 引擎核心功能依赖) 以及所有 org/jetbrains/kotlin/** 内容.
    exclude(
      "org/slf4j/**",
      "META-INF/services/org.slf4j*",
      "META-INF/services/org/slf4j*",
      "META-INF/maven/org.slf4j/**",
      "META-INF/*.SF",
      "META-INF/*.RSA",
      "META-INF/*.DSA",
      "META-INF/*.EC",
    )
  }
}

tasks.register<Copy>("copyDep") {
  val workingDir = projectDir.resolve("sandbox")
  duplicatesStrategy = DuplicatesStrategy.WARN
  into("$workingDir/lib")
  // runtimeClasspath 排除原始 kotlin-main-kts; 用 patched 版本顶上 (文件名一致 -> manifest Class-Path 不需改).
  from(configurations.runtimeClasspath) {
    exclude { it.file.name.startsWith("kotlin-main-kts-") && it.file.name.endsWith(".jar") }
  }
  from(patchedKotlinMainKts)
  from("lib")
}
//tasks.withType<ShadowJar> {
//  dependsOn("distTar", "distZip")
//  archiveFileName.set("${project.name}-${project.version}.jar")
//}
tasks.register("copyToPlugins") {
  doLast {
    val pluginDir = rootProject.subprojects.first { it.name == "arona-core" }.projectDir.path + "/sandbox"
    val buildJar = project.layout.buildDirectory.dir("libs").get().asFile
      .listFiles { it -> it.isFile && it.name.contains(version.toString()) }
      ?.firstOrNull()
    if (buildJar == null) {
      logger.error("build file not found: ${project.name}")
    } else {
      // 删除旧版本插件
      file(pluginDir)
        .listFiles { it -> it.isFile && it.name.startsWith(project.name) }
        ?.forEach { it.delete() }
      buildJar.copyTo(file(pluginDir + "./" + buildJar.name), true)
      logger.error("copy ${buildJar.name} to sanbox folder")
    }
  }
}

dependencies {
  // Netty BOM: 把所有 io.netty:* 强制对齐到 4.2.x, 解决 lib/ 启动时的跨版本类冲突.
  // 背景: Ktor 3.4.3 通过 ktor-bom 引入 netty-codec-base/-compression/-http/-http2/-handler 4.2.12 (Netty 4.2 把
  // 旧 fat netty-codec 拆成多个子模块); 而 kreds 0.9.1 直接依赖 netty-codec-redis 4.1.86, 后者强制要求 fat
  // netty-codec 4.1.86. Gradle 不把 netty-codec 与 netty-codec-base/-http/... 视为同一 artifact, 默认解析下
  // 4.1.86 fat jar 与 4.2.12 split jars 共存, io.netty.handler.codec.http.* 等包在 classpath 上出现两份不同版本
  // 的同名类. Netty pipeline 初始化或写出 HTTP 响应时按运行时实际加载到的混版本类调用方法, 触发
  // NoSuchMethodError/AbstractMethodError, channel exceptionCaught 关闭连接, 客户端表现为 "Empty reply from
  // server". IDEA 直跑可能因 classpath 顺序差异恰好规避, 打包后从 lib/ 启动 (manifest Class-Path 顺序不同) 必现.
  // 加 BOM 后, kreds 传递的 netty-codec-redis / netty-handler 经 Gradle BOM constraint + highest-version-wins
  // 升到 4.2.12.Final, 旧 fat netty-codec artifact 不再进入最终依赖图, lib/ 不再出现任何 netty-*-4.1.x.jar.
  // 用 api 而非 implementation: BOM 仅声明版本约束、不暴露代码符号, 通过 api 让下游 plugin 的 compile/runtime
  // classpath 也共享同一套 Netty 版本对齐, 防止 plugin 侧通过 kreds (api) 透传到 4.1.x Netty 类签名.
  api(platform("io.netty:netty-bom:4.2.12.Final"))

  // kts: kotlin scripting + Ivy 协同支撑 .kts 脚本依赖解析
  api(libs.bundles.kotlin.scripting.base)
  api(libs.ivy)

  implementation(libs.bundles.ktor.server.base)
  implementation(libs.bundles.ktor.client.base)
  implementation(libs.ktor.serialization.kotlinx.json.jvm)

  api(libs.bundles.exposed.base)
  api(libs.mongodb.driver.kotlin.coroutine)
  // bson-kotlinx 提供 KotlinSerializerCodecProvider, 仅 host 自身构造 Mongo codec registry 时需要,
  // 不必传递给下游 plugin 的编译 classpath, 故走 implementation.
  implementation(libs.mongodb.bson.kotlinx)

  api(libs.kreds)

  implementation(libs.jline)
  implementation(libs.caffeine)
  implementation(libs.atomicfu)
  implementation(libs.bcprov.jdk18on)
  implementation(libs.bundles.kotlinx.serialization.base)
  api(libs.mariadb.java.client)
  api(libs.sqlite.jdbc)
  api(libs.kaml)
  api(libs.semver)
  api(libs.reflections)
  api(libs.clikt)
  // P2-C2: Mordant 与 Jansi 由本地 lib/ jar 升级到 Maven 坐标; Clikt 5 transitively 拉 Mordant 3,
  // 这里显式声明是因为项目代码层 (CommandMain / KeepInputConsole) 直接 import Mordant API.
  api(libs.mordant)
  implementation(libs.jansi)
  api(libs.kotlinx.datetime)

  api(libs.bundles.kotlinx.coroutines.base)
  api(libs.bundles.logback.base)

  testImplementation(libs.ktor.server.test.host)
  testImplementation(kotlin("test-junit"))
}

tasks.withType<Jar> {
  manifest.attributes["Main-Class"] = "com.diyigemt.arona.ApplicationKt"
  doFirst {
    manifest.attributes["Class-Path"] = configurations.runtimeClasspath.get().files.toList().joinToString(" ") { "lib/${it.name}" }
  }
  duplicatesStrategy = DuplicatesStrategy.WARN
}
