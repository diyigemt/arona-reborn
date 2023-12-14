package com.diyigemt.arona.utils

import io.github.z4kn4fein.semver.Version
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.UUID
import kotlin.reflect.KClass

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
fun Instant.toDate() = toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
/**
 * 获取当前时间 HH:mm:ss
 */
fun currentTime() = currentLocalDateTime().time.toSecond()
fun LocalTime.toSecond() = toString().substringBeforeLast(".")
fun Instant.toTime() = toLocalDateTime(TimeZone.currentSystemDefault()).time.toSecond()

/**
 * 获取当前日期和时间 yyyy-MM-dd HH:mm:ss
 */
fun currentDateTime() = currentLocalDateTime().let { "${it.date} ${it.time.toSecond()}" }

fun Instant.toDateTime() = "${toDate()} ${toTime()}"

/**
 * 将形如 yyyy-MM-dd HH:mm:ss 格式的字符串转化回Instant
 */
fun datetimeToInstant(datetime: String) =
  datetime.replace(" ", "T").toLocalDateTime().toInstant(TimeZone.currentSystemDefault())

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

fun uuid(prefix: String = "") = if (prefix.isNotBlank()) "$prefix.${UUID.randomUUID()}" else UUID.randomUUID().toString()

val JsonIgnoreUnknownKeys = Json { ignoreUnknownKeys = true }

val KClass<*>.name
  get() = simpleName ?: qualifiedName ?: "<anonymous>"
