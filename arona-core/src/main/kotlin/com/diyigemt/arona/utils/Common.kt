package com.diyigemt.arona.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import kotlinx.datetime.*
import io.github.z4kn4fein.semver.Version
import org.slf4j.Logger

typealias SemVersion = Version

/**
 * 获取kotlin时间对象
 */
fun now() = Clock.System.now()
fun currentLocalDateTime() = now().toLocalDateTime(TimeZone.currentSystemDefault())
/**
 * 获取自1970-01-01以来的秒数
 */
fun currentTimestamp() = now().epochSeconds
/**
 * 获取当前日期 yyyy-MM-dd
 */
fun currentDate() = currentLocalDateTime().date.toString()
/**
 * 获取当前时间 HH:mm:ss
 */
fun currentTime() = currentLocalDateTime().time.toSecond()
fun LocalTime.toSecond() = toString().substringBeforeLast(".")
/**
 * 获取当前日期和时间 yyyy-MM-dd HH:mm:ss
 */
fun currentDateTime() = currentLocalDateTime().let { "${it.date} ${it.time.toSecond()}" }

/**
 * 将形如 yyyy-MM-dd HH:mm:ss 格式的字符串转化回Instant
 */
fun datetimeToInstant(datetime: String) = datetime.replace(" ", "T").toLocalDateTime().toInstant(TimeZone.currentSystemDefault())

private val cpuPool = CoroutineScope(Job() + Dispatchers.Default)
private val ioPool = CoroutineScope(Job() + Dispatchers.IO)
fun runCpuSuspend(block: suspend () -> Unit) = cpuPool.launch {
  block()
}
fun runSuspend(block: suspend () -> Unit) = ioPool.launch {
  block()
}

val PipelineContext<Unit, ApplicationCall>.isJsonPost
  get() = context.request.header(HttpHeaders.ContentType) == "application/json"

fun Logger.error(exception: Throwable) = error(exception.message ?: "Exception of type ${exception::class}", exception)
