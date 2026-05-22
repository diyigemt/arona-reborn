package com.diyigemt.arona.webui.pluginconfig

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.database.permission.toMongodbKey
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.webui.event.ContentAuditEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 固化 [preparePluginConfigWrite] 命令侧写入守卫的行为:
 *   1. rawKey 校验 (空 / `.` / `$`) → InvalidKey
 *   2. [PluginWebuiConfig.check] reject → CheckRejected + fieldErrors 透传
 *   3. audit 命中 → AuditBlocked; audit=false 时绕过审核
 *   4. canonical 化: alias 入参写入归一到主 key; 未注册 fallthrough 用 rawKey
 *
 * 不拉起 Mongo / endpoint, 仅测试 prepare 层. PermissionService 为内存 ConcurrentHashMap,
 * 每个 case 用独立 namespace 防 registry 污染.
 */
class PluginConfigWriteTest {

  // --- fixtures ---

  @Serializable
  @PluginConfigId(id = "v2", aliases = ["v1"])
  private class WriteConfigWithAlias(val flag: Boolean = false) : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "plain")
  private class WriteConfigPlain : PluginWebuiConfig()

  /** 主动 reject 的配置, 用于覆盖 check 失败路径. */
  @Serializable
  @PluginConfigId(id = "checked")
  private class WriteConfigRejecting : PluginWebuiConfig() {
    override fun check(): PluginConfigCheckResult = PluginConfigCheckResult.PluginConfigCheckReject(
      message = "不允许保存",
      fieldErrors = listOf(FieldError(path = "$.flag", message = "field rejected")),
    )
  }

  // --- helpers ---

  private class StubCommandOwner(private val ns: String) : CommandOwner {
    override val permission: Permission by lazy {
      PermissionService.register(permissionId("*"), "test owner $ns")
    }
    override fun permissionId(name: String): PermissionId = PermissionId(ns, name)
  }

  private companion object {
    private val nsCounter = AtomicLong()
  }

  private fun freshOwner(): StubCommandOwner =
    StubCommandOwner("test.writer.${nsCounter.incrementAndGet()}")

  private fun namespaceOf(owner: StubCommandOwner): String =
    owner.permission.id.nameSpace.toMongodbKey()

  /** audit stub: 命中给定 keyword 就 reject; 用于隔离全局事件总线. */
  private fun blockingAuditor(keyword: String): suspend (ContentAuditEvent) -> ContentAuditEvent = { ev ->
    ev.apply {
      if (keyword in ev.value) {
        pass = false
        message = "命中关键词 $keyword"
      }
    }
  }

  private val passThroughAuditor: suspend (ContentAuditEvent) -> ContentAuditEvent = { it }

  // --- 1. rawKey 安全校验 ---

  @Test
  fun `rawKey 为空时抛 InvalidKey`() = runTest {
    val ex = assertFailsWith<PluginConfigWriteRejectedException> {
      preparePluginConfigWrite(
        pluginNamespace = "anyns",
        rawKey = "",
        value = WriteConfigPlain(),
        serializer = WriteConfigPlain.serializer(),
        audit = false,
      )
    }
    assertEquals(PluginConfigWriteRejectedException.Kind.InvalidKey, ex.kind)
  }

  @Test
  fun `rawKey 含点号时抛 InvalidKey`() = runTest {
    val ex = assertFailsWith<PluginConfigWriteRejectedException> {
      preparePluginConfigWrite(
        pluginNamespace = "anyns",
        rawKey = "bad.key",
        value = WriteConfigPlain(),
        serializer = WriteConfigPlain.serializer(),
        audit = false,
      )
    }
    assertEquals(PluginConfigWriteRejectedException.Kind.InvalidKey, ex.kind)
  }

  @Test
  fun `rawKey 含美元符时抛 InvalidKey`() = runTest {
    val ex = assertFailsWith<PluginConfigWriteRejectedException> {
      preparePluginConfigWrite(
        pluginNamespace = "anyns",
        rawKey = "bad\$key",
        value = WriteConfigPlain(),
        serializer = WriteConfigPlain.serializer(),
        audit = false,
      )
    }
    assertEquals(PluginConfigWriteRejectedException.Kind.InvalidKey, ex.kind)
  }

  // --- 2. check reject 路径 ---

  @Test
  fun `check reject 时抛 CheckRejected 并保留 fieldErrors`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigRejecting.serializer())
    val ex = assertFailsWith<PluginConfigWriteRejectedException> {
      preparePluginConfigWrite(
        pluginNamespace = namespaceOf(owner),
        rawKey = "checked",
        value = WriteConfigRejecting(),
        serializer = WriteConfigRejecting.serializer(),
        audit = false,
      )
    }
    assertEquals(PluginConfigWriteRejectedException.Kind.CheckRejected, ex.kind)
    assertEquals(1, ex.fieldErrors.size, "fieldErrors 应透传到异常")
    assertEquals("$.flag", ex.fieldErrors.single().path)
  }

  // --- 3. audit 路径 ---

  @Test
  fun `audit 命中时抛 AuditBlocked`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigWithAlias.serializer())
    val ex = assertFailsWith<PluginConfigWriteRejectedException> {
      preparePluginConfigWrite(
        pluginNamespace = namespaceOf(owner),
        rawKey = "v2",
        value = WriteConfigWithAlias(flag = true),
        serializer = WriteConfigWithAlias.serializer(),
        audit = true,
        // 直接在 json 里能命中的关键词: 配置序列化必然带 "flag" 字段名
        auditor = blockingAuditor(keyword = "flag"),
      )
    }
    assertEquals(PluginConfigWriteRejectedException.Kind.AuditBlocked, ex.kind)
    assertTrue(ex.message.contains("命中关键词"), "审核拒绝原因应写入 message: ${ex.message}")
  }

  @Test
  fun `auditor 抛异常时 fail-open 放行`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigWithAlias.serializer())
    // auditor 抛异常会被 auditOrAllow 捕获并返回 null, prepare 端 ev==null 不应当抛, 与 endpoint fail-open 语义一致.
    val prepared = preparePluginConfigWrite(
      pluginNamespace = namespaceOf(owner),
      rawKey = "v2",
      value = WriteConfigWithAlias(),
      serializer = WriteConfigWithAlias.serializer(),
      audit = true,
      auditor = { throw RuntimeException("auditor down") },
    )
    assertEquals("v2", prepared.canonicalKey)
  }

  @Test
  fun `audit=false 时即便 auditor 会拦也直接放行`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigWithAlias.serializer())
    // 即便注入会拦截的 auditor, audit=false 应导致 auditor 根本不被调用.
    val prepared = preparePluginConfigWrite(
      pluginNamespace = namespaceOf(owner),
      rawKey = "v2",
      value = WriteConfigWithAlias(flag = true),
      serializer = WriteConfigWithAlias.serializer(),
      audit = false,
      auditor = blockingAuditor(keyword = "flag"),
    )
    assertEquals("v2", prepared.canonicalKey)
  }

  // --- 4. canonical 化 / fallthrough ---

  @Test
  fun `alias 入参写入归一到 primary key`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigWithAlias.serializer())
    val prepared = preparePluginConfigWrite(
      pluginNamespace = namespaceOf(owner),
      rawKey = "v1",                 // 用 alias 入参
      value = WriteConfigWithAlias(),
      serializer = WriteConfigWithAlias.serializer(),
      audit = true,
      auditor = passThroughAuditor,
    )
    assertEquals("v2", prepared.canonicalKey, "alias 入参必须归一到主 key, 否则数据被分裂到 alias")
  }

  @Test
  fun `未注册 key 写入退化到 rawKey`() = runTest {
    val owner = freshOwner()
    // 故意不 register, 模拟"插件传一个手工 key" / 反射扫描未覆盖的场景
    val prepared = preparePluginConfigWrite(
      pluginNamespace = namespaceOf(owner),
      rawKey = "unregistered_key",
      value = WriteConfigPlain(),
      serializer = WriteConfigPlain.serializer(),
      audit = false,
    )
    assertEquals("unregistered_key", prepared.canonicalKey)
  }

  @Test
  fun `prepared json 为 serializer 标准编码`() = runTest {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, WriteConfigWithAlias.serializer())
    val prepared = preparePluginConfigWrite(
      pluginNamespace = namespaceOf(owner),
      rawKey = "v2",
      value = WriteConfigWithAlias(flag = true),
      serializer = WriteConfigWithAlias.serializer(),
      audit = false,
    )
    val decoded = Json.parseToJsonElement(prepared.json)
    assertTrue(decoded.toString().contains("\"flag\":true"), "json 应包含 flag=true: ${prepared.json}")
  }
}
