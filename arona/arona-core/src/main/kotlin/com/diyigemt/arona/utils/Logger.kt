package com.diyigemt.arona.utils

import io.ktor.util.logging.*

// 所有api调用的logger
internal val apiLogger = KtorSimpleLogger("com.diyigemt.arona.ui")

// 响应用户消息的logger
internal val userLogger = KtorSimpleLogger("com.diyigemt.arona.user")

// 命令行的logger
internal val commandLineLogger = KtorSimpleLogger("com.diyigemt.arona.commandline")

// debug的logger
internal val debugLogger = KtorSimpleLogger("com.diyigemt.arona.debugger")

inline fun Logger.error(message: () -> String?) = error(message())
inline fun Logger.debug(message: () -> String?) = debug(message())
