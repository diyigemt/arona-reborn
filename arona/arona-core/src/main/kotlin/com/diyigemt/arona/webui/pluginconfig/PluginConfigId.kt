package com.diyigemt.arona.webui.pluginconfig

import com.diyigemt.arona.utils.JsonIgnoreUnknownKeys
import com.diyigemt.arona.webui.event.ContentAuditEvent
import com.diyigemt.arona.webui.event.auditOrAllow
import com.diyigemt.arona.webui.event.isBlock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 显式声明 [PluginWebuiConfig] 子类的稳定持久化 id 与历史 aliases.
 *
 * 不标注时, 主 id 退化到 `serialName.substringAfterLast('.')` (与旧 simpleName 行为等价),
 * 保持向后兼容.
 *
 * Why: 历史实现把 simpleName 当持久化 key, 类一旦重命名/移包/被 R8 混淆, 旧用户配置就读不到了;
 * 同时注册侧 (`serialName.split(".").last()`) 与命令侧 (`T::class.name`) 是两条独立路径,
 * 加 `@SerialName` 后会分叉. 引入注解后:
 *   - 主 id 决定写入位置, aliases 决定读路径回退;
 *   - [resolveConfigKey] 让注册侧 / 命令侧共用一套 key 生成.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PluginConfigId(
  val id: String,
  val aliases: Array<String> = [],
)

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.pluginConfigIdAnnotation(): PluginConfigId? =
  annotations.filterIsInstance<PluginConfigId>().firstOrNull()

/**
 * 计算 `(pluginId, configKey)` 二元组中的内层 key.
 * 标了 [PluginConfigId] 一律使用注解 id (即便是 blank/含禁字符, 由注册期 validate fail-fast);
 * 未标注解时退化到 `serialName` 末尾段, 与历史行为一致.
 *
 * `@PublishedApi internal`: 命令侧 inline 默认 key 会用到, 需要在跨模块 inline 展开后仍可访问.
 */
@OptIn(ExperimentalSerializationApi::class)
@PublishedApi
internal fun resolveConfigKey(serializer: KSerializer<*>): String {
  val descriptor = serializer.descriptor
  descriptor.pluginConfigIdAnnotation()?.let { return it.id }
  return descriptor.serialName.substringAfterLast('.')
}

/**
 * 不在这里过滤空白 alias —— 留给 [PluginWebuiConfigRecorder] 的注册校验启动期 fail-fast,
 * 避免开发者写 `aliases = [""]` 被静默吞掉.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun resolveConfigAliases(serializer: KSerializer<*>): List<String> =
  serializer.descriptor.pluginConfigIdAnnotation()
    ?.aliases
    ?.asList()
    ?: emptyList()

/**
 * 命令侧/endpoint 走 [preparePluginConfigWrite] 失败时抛出.
 * 用 [Kind] 区分原因, 让命令框架或 endpoint 能机器可读地映射到日志/错误码,
 * 而不是再去解 message 字符串.
 */
class PluginConfigWriteRejectedException(
  val kind: Kind,
  override val message: String,
  val fieldErrors: List<FieldError> = emptyList(),
) : IllegalStateException(message) {
  enum class Kind {
    /** 用户提供的 key 含禁字符 / 空白. */
    InvalidKey,
    /** [PluginWebuiConfig.check] 主动 reject. */
    CheckRejected,
    /** 内容审核命中. */
    AuditBlocked,
  }
}

/**
 * [preparePluginConfigWrite] 的产出: 已 canonical 化、可直接落库的写入候选.
 *
 * - [canonicalKey] alias 入参归一后的主 key, raw `updatePluginConfig` 直接拿去拼 Mongo dot-path
 * - [json] 标准 JSON 文本; 给 audit / 日志用, 也是 [element] 的派生源 (单一权威源)
 * - [element] 给 Mongo 落库用; 经过 [KotlinxJsonElementCodecProvider][com.diyigemt.arona.database.KotlinxJsonElementCodecProvider]
 *   编成原生 BSON Document. 与 [json] 完全等价, 不能各自被修改.
 */
data class PreparedPluginConfigWrite(
  val canonicalKey: String,
  val json: String,
  val element: JsonObject,
)

/**
 * 命令侧 inline `updatePluginConfig<T>` 与 endpoint POST 的共同写入准备层.
 *
 * 旧实现里命令侧只做 `encodeToString` 后直接 Mongo `$set`, 完全绕过 webui 那边的
 * `check / audit / canonical` 三道关. 本函数把这三件事 + Mongo path 安全校验集中:
 *
 *   1. [PluginWebuiConfigRecorder.requireSafeRuntimeKey] 拦下含 `.` / `$` / 空白的 key
 *      (注册期校验对未注册 fallthrough 的 key 不生效, 必须在写时再补一道)
 *   2. [PluginWebuiConfig.check] reject → [PluginConfigWriteRejectedException.Kind.CheckRejected]
 *   3. canonical 化: 注册了就归一到主 key; 未注册则保持原 key 落库 (反射注册不是绝对契约,
 *      也允许插件自由 key 重载)
 *   4. [audit]=true 时跑 [auditOrAllow], 命中拦截 → [PluginConfigWriteRejectedException.Kind.AuditBlocked].
 *      命中 fail-open 语义: timeout/异常 (`ev == null`) 不拒绝, 与 endpoint 写路径同款.
 *
 * 命令侧热路径 (如 GachaCommand 抽卡计数) 写的多半是机器派生状态, 调用方可以传 `audit=false`
 * 规避 3s audit 超时叠加到响应延迟. endpoint 仍默认 audit=true 强制审核.
 *
 * 失败语义用异常: 命令侧调用栈天然走异常通道, 改 sealed result 会对所有调用站点造成 churn 且
 * 容易被忽略; endpoint 端可以 catch 转 [com.diyigemt.arona.utils.errorMessage].
 *
 * [auditor] 与 [auditOrAllow] 的 `auditor` 形参语义一致, 仅供测试注入故障/拦截行为.
 *
 * `@PublishedApi internal` 是因为这函数会被命令侧 inline 入口跨模块展开, 必须在外部模块可访问.
 */
@PublishedApi
internal suspend fun <T : PluginWebuiConfig> preparePluginConfigWrite(
  pluginNamespace: String,
  rawKey: String,
  value: T,
  serializer: KSerializer<T>,
  audit: Boolean = true,
  auditor: (suspend (ContentAuditEvent) -> ContentAuditEvent)? = null,
): PreparedPluginConfigWrite {
  try {
    PluginWebuiConfigRecorder.requireSafeRuntimeKey(rawKey)
  } catch (e: IllegalArgumentException) {
    throw PluginConfigWriteRejectedException(
      kind = PluginConfigWriteRejectedException.Kind.InvalidKey,
      message = e.message ?: "invalid plugin config key",
    )
  }

  when (val checkResult = value.check()) {
    is PluginConfigCheckResult.PluginConfigCheckReject ->
      throw PluginConfigWriteRejectedException(
        kind = PluginConfigWriteRejectedException.Kind.CheckRejected,
        message = checkResult.message,
        fieldErrors = checkResult.fieldErrors,
      )
    is PluginConfigCheckResult.PluginConfigCheckAccept -> Unit
  }

  val json = JsonIgnoreUnknownKeys.encodeToString(serializer, value)
  // 单一权威源是 json; element 从它派生. 派生失败 (top-level 不是 JsonObject 或 leaf key 含 `.`/`$`)
  // 一律转 InvalidKey: 这是 schema 级别的写入违例, 不应当走到 Mongo.
  val element = try {
    JsonIgnoreUnknownKeys.parseToJsonElement(json).jsonObject.also {
      PluginWebuiConfigRecorder.requireSafeBsonLeafKeys(it)
    }
  } catch (e: IllegalArgumentException) {
    throw PluginConfigWriteRejectedException(
      kind = PluginConfigWriteRejectedException.Kind.InvalidKey,
      message = e.message ?: "invalid plugin config leaf key",
    )
  }
  val canonicalKey = PluginWebuiConfigRecorder.canonicalKeyOf(pluginNamespace, rawKey) ?: rawKey

  if (audit) {
    val ev = if (auditor != null) auditOrAllow(json, auditor = auditor) else auditOrAllow(json)
    if (ev?.isBlock == true) {
      throw PluginConfigWriteRejectedException(
        kind = PluginConfigWriteRejectedException.Kind.AuditBlocked,
        message = "内容审核拒绝: ${ev.message}",
      )
    }
  }

  return PreparedPluginConfigWrite(canonicalKey, json, element)
}
