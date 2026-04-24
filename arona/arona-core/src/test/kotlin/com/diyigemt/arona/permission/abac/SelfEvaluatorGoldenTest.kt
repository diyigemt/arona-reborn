package com.diyigemt.arona.permission.abac

import com.diyigemt.arona.database.permission.Policy
import com.diyigemt.arona.database.permission.PolicyNodeEffect
import com.diyigemt.arona.permission.abac.compile.PolicyCompiler
import com.diyigemt.arona.permission.abac.eval.PolicyEvaluator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 自研 evaluator 对同一份 golden JSON 的回归测试. 与 warden 基线 ([com.diyigemt.security.ABACGoldenTest])
 * 相比有两处预期差异:
 *
 * 1. **IS_CHILD 新语义**: 非 ".*"/非单 "*" 形式的 `*` 不再是通配符, `R=""` 显式 deny 等. 当前 golden
 *    不包含触发这些差异的 case, 所以全部 allow/deny 判断一致.
 * 2. **类型不匹配不再抛异常**: warden 遇到 `BadExpressionException` 会返回 error, 自研直接返回 false -> deny.
 *    因此所有 `expected.outcome == "error"` 的 case, 自研下会变 `deny`. 测试容忍这个差异.
 *
 * 其他 allow/deny 必须 bit-exact 一致.
 */
class SelfEvaluatorGoldenTest {
  private val json = Json { ignoreUnknownKeys = false }

  @Test
  fun `自研 evaluator 跑黄金用例与 warden 基线对齐`() {
    val files = goldenFiles()
    assertTrue(files.isNotEmpty(), "未找到黄金用例 JSON")

    val failures = mutableListOf<String>()
    var total = 0
    files.forEach { path ->
      val golden = runCatching { json.decodeFromString<GoldenFile>(path.readText()) }
        .getOrElse { fail("解析黄金文件 $path 失败: ${it.message}") }
      golden.cases.forEach { case ->
        total++
        val expectedSelf = when (case.expected.outcome) {
          GoldenOutcome.allow -> SelfOutcome.Allow
          GoldenOutcome.deny -> SelfOutcome.Deny
          // 自研永不抛异常, warden error 映射为 self deny
          GoldenOutcome.error -> SelfOutcome.Deny
        }
        val actual = runSelfEvaluator(case)
        if (actual != expectedSelf) {
          failures += "case '${case.id}' in ${path.fileName}: expected=$expectedSelf, actual=$actual"
        }
      }
    }
    assertEquals(
      emptyList(),
      failures,
      "自研 evaluator 与 warden 基线 ${failures.size}/$total 条不一致:\n" + failures.joinToString("\n")
    )
  }

  private fun runSelfEvaluator(case: GoldenCase): SelfOutcome {
    val allow = case.policies
      .filter { it.effect == PolicyNodeEffect.ALLOW }
      .flatMap { PolicyCompiler.compile(it) }
    val deny = case.policies
      .filter { it.effect == PolicyNodeEffect.DENY }
      .flatMap { PolicyCompiler.compile(it) }
    val req = AbacRequest(
      subject = case.request.subject.mapValues { it.value.toAny() },
      action = case.request.action.mapValues { it.value.toAny() },
      resource = case.request.resource.mapValues { it.value.toAny() },
      environment = case.request.environment.mapValues { it.value.toAny() },
    )
    return when (PolicyEvaluator.evaluate(allow, deny, req)) {
      is Decision.Permit -> SelfOutcome.Allow
      is Decision.Deny -> SelfOutcome.Deny
    }
  }

  private fun goldenFiles(): List<Path> {
    val paths = mutableListOf<Path>()
    val urls = javaClass.classLoader.getResources("abac/golden")
    while (urls.hasMoreElements()) {
      val url = urls.nextElement()
      require(url.protocol == "file") { "unsupported protocol: ${url.protocol}" }
      Files.walk(Paths.get(url.toURI())).use { stream ->
        stream
          .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
          .forEach(paths::add)
      }
    }
    return paths.sortedBy { it.toString() }
  }

  private fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toAny() }
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> when {
      isString -> content
      booleanOrNull != null -> booleanOrNull
      longOrNull != null -> longOrNull
      doubleOrNull != null -> doubleOrNull
      else -> content
    }
  }

  private enum class SelfOutcome { Allow, Deny }
}
