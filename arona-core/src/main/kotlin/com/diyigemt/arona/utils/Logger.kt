package com.diyigemt.arona.utils

import io.ktor.util.logging.*
// 所有api调用的logger
val apiLogger = KtorSimpleLogger("com.diyigemt.arona.ui")
// 响应用户消息的logger
val userLogger = KtorSimpleLogger("com.diyigemt.arona.user")
// 命令行的logger
val commandLineLogger = KtorSimpleLogger("com.diyigemt.arona.commandline")
