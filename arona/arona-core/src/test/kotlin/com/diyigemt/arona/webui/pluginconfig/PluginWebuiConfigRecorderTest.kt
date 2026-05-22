package com.diyigemt.arona.webui.pluginconfig

import com.diyigemt.arona.command.CommandOwner
import com.diyigemt.arona.database.permission.toMongodbKey
import com.diyigemt.arona.permission.Permission
import com.diyigemt.arona.permission.PermissionId
import com.diyigemt.arona.permission.PermissionService
import com.diyigemt.arona.webui.endpoints.plugin.PluginPreferenceResp
import com.diyigemt.arona.webui.pluginconfig.PluginWebuiConfigRecorder.DataSafetyResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 固化 PluginWebuiConfigRecorder 三处加固后的行为:
 *   1. 注册期 validateConfigKey 拒绝非法字符 (`.` / `$` / blank)
 *   2. 注册期 assertNoCollision 拒绝同 namespace 内的 primary/alias 冲突
 *   3. siblingKeysFor 与 checkDataSafety 的 alias → primary canonical 化
 *
 * 没拉起 Mongo / Ktor; 每个测试用独立 namespace 防止 registry 互污,
 * PermissionService 本身是轻量内存 ConcurrentHashMap, 直接复用.
 */
class PluginWebuiConfigRecorderTest {

  // --- fixture configs ---

  @Serializable
  @PluginConfigId(id = "config_v2", aliases = ["config_v1"])
  private class CfgPrimaryWithAlias : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "")
  private class CfgBlankId : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "   ")
  private class CfgWhitespaceId : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "bad.id")
  private class CfgDottedId : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "bad\$id")
  private class CfgDollarId : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "ok", aliases = [""])
  private class CfgBlankAlias : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "ok", aliases = ["bad.alias"])
  private class CfgDottedAlias : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "ok", aliases = ["bad\$alias"])
  private class CfgDollarAlias : PluginWebuiConfig()

  @Serializable
  @PluginConfigId(id = "dup", aliases = ["dup"])
  private class CfgSelfCollision : PluginWebuiConfig()

  // 与 CfgPrimaryWithAlias 在同 namespace 下重新注册时, primary "config_v2" 撞 primary.
  @Serializable
  @PluginConfigId(id = "config_v2", aliases = ["other"])
  private class CfgPrimaryClashPrimary : PluginWebuiConfig()

  // primary "config_v1" 撞 CfgPrimaryWithAlias 已注册的 alias.
  @Serializable
  @PluginConfigId(id = "config_v1")
  private class CfgPrimaryClashAlias : PluginWebuiConfig()

  // alias "config_v2" 撞 CfgPrimaryWithAlias 的 primary.
  @Serializable
  @PluginConfigId(id = "other2", aliases = ["config_v2"])
  private class CfgAliasClashPrimary : PluginWebuiConfig()

  // --- helpers ---

  private class StubCommandOwner(private val ns: String) : CommandOwner {
    override val permission: Permission by lazy {
      PermissionService.register(permissionId("*"), "test owner $ns")
    }
    override fun permissionId(name: String): PermissionId = PermissionId(ns, name)
  }

  // 必须跨测试方法共享, 否则 JUnit 每个方法 new 实例会重置 counter 导致 ns 撞 PermissionService 全局表.
  private companion object {
    private val nsCounter = AtomicLong()
  }

  private fun freshOwner(): StubCommandOwner =
    StubCommandOwner("test.recorder.${nsCounter.incrementAndGet()}")

  private fun namespaceOf(owner: StubCommandOwner): String =
    owner.permission.id.nameSpace.toMongodbKey()

  // --- 1. validateConfigKey ---

  @Test
  fun `primary id 为空时注册抛出`() {
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgBlankId.serializer())
    }
  }

  @Test
  fun `primary id 为空白字符时注册抛出`() {
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgWhitespaceId.serializer())
    }
  }

  @Test
  fun `primary id 含点号时注册抛出`() {
    val err = assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgDottedId.serializer())
    }
    assertTrue(err.message!!.contains("'.'"), "错误信息应指出禁字符: ${err.message}")
  }

  @Test
  fun `primary id 含美元符时注册抛出`() {
    val err = assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgDollarId.serializer())
    }
    assertTrue(err.message!!.contains("\$"), "错误信息应指出禁字符: ${err.message}")
  }

  @Test
  fun `alias 为空字符串时注册抛出`() {
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgBlankAlias.serializer())
    }
  }

  @Test
  fun `alias 含点号时注册抛出`() {
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgDottedAlias.serializer())
    }
  }

  @Test
  fun `alias 含美元符时注册抛出`() {
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgDollarAlias.serializer())
    }
  }

  // --- 2. assertNoCollision ---

  @Test
  fun `同次注册内 primary 与 alias 重复时抛出`() {
    val err = assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(freshOwner(), CfgSelfCollision.serializer())
    }
    assertTrue(err.message!!.contains("Duplicate"), "错误信息应提示自重复: ${err.message}")
  }

  @Test
  fun `同 namespace 二次注册 primary 撞已有 primary 时抛出`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val err = assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(owner, CfgPrimaryClashPrimary.serializer())
    }
    assertTrue(err.message!!.contains("collision"), "错误信息应提示冲突: ${err.message}")
  }

  @Test
  fun `同 namespace 二次注册 primary 撞已有 alias 时抛出`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(owner, CfgPrimaryClashAlias.serializer())
    }
  }

  @Test
  fun `同 namespace 二次注册 alias 撞已有 primary 时抛出`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    assertFailsWith<IllegalArgumentException> {
      PluginWebuiConfigRecorder.register(owner, CfgAliasClashPrimary.serializer())
    }
  }

  // --- 3. siblingKeysFor ---

  @Test
  fun `siblingKeysFor 传 primary 时返回 aliases`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val siblings = PluginWebuiConfigRecorder.siblingKeysFor(namespaceOf(owner), "config_v2")
    assertEquals(listOf("config_v1"), siblings)
  }

  @Test
  fun `siblingKeysFor 传 alias 时返回 primary 但不含自身`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val siblings = PluginWebuiConfigRecorder.siblingKeysFor(namespaceOf(owner), "config_v1")
    assertEquals(listOf("config_v2"), siblings)
  }

  @Test
  fun `siblingKeysFor 传不存在的 key 返回空`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    assertEquals(emptyList(), PluginWebuiConfigRecorder.siblingKeysFor(namespaceOf(owner), "ghost"))
  }

  @Test
  fun `siblingKeysFor 命中未注册的 plugin 返回空`() {
    assertEquals(emptyList(), PluginWebuiConfigRecorder.siblingKeysFor("ghost.plugin", "anykey"))
  }

  // --- 4. checkDataSafety canonical 化 ---

  @Test
  fun `checkDataSafety 收到 alias key 时返回主 key`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val resp = PluginPreferenceResp(id = namespaceOf(owner), key = "config_v1", value = JsonObject(emptyMap()))
    val result = PluginWebuiConfigRecorder.checkDataSafety(resp)
    val ok = assertIs<DataSafetyResult.Ok>(result)
    assertEquals("config_v2", ok.canonicalKey, "alias 入参必须 canonical 化为 primary")
  }

  @Test
  fun `checkDataSafety 收到主 key 时 canonicalKey 保持不变`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val resp = PluginPreferenceResp(id = namespaceOf(owner), key = "config_v2", value = JsonObject(emptyMap()))
    val result = PluginWebuiConfigRecorder.checkDataSafety(resp)
    val ok = assertIs<DataSafetyResult.Ok>(result)
    assertEquals("config_v2", ok.canonicalKey)
  }

  @Test
  fun `checkDataSafety 收到未注册 key 时返回 Err`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val resp = PluginPreferenceResp(id = namespaceOf(owner), key = "ghost", value = JsonObject(emptyMap()))
    assertIs<DataSafetyResult.Err>(PluginWebuiConfigRecorder.checkDataSafety(resp))
  }

  // --- generateSchema 也走 canonical 化 ---

  @Test
  fun `generateSchema 用 alias 查得 schema 且 configKey 为主 key`() {
    val owner = freshOwner()
    PluginWebuiConfigRecorder.register(owner, CfgPrimaryWithAlias.serializer())
    val schema = PluginWebuiConfigRecorder.generateSchema(namespaceOf(owner), "config_v1")
    assertEquals("config_v2", schema?.configKey, "alias 入参 schema 也必须吐主 key")
  }
}
