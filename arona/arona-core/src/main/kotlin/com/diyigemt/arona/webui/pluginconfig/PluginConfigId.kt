package com.diyigemt.arona.webui.pluginconfig

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.SerialDescriptor

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
