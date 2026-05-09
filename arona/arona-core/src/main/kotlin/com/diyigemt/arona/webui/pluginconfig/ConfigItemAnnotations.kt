@file:OptIn(ExperimentalSerializationApi::class)

package com.diyigemt.arona.webui.pluginconfig

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * 标注 [PluginWebuiConfig] 子类中的可配置字段, 由 [SchemaGenerator] 在生成 schema 时读取.
 *
 * 必须用 [SerialInfo] 让 kotlinx.serialization 把注解附加到 SerialDescriptor 上,
 * 否则 [kotlinx.serialization.descriptors.SerialDescriptor.getElementAnnotations] 拿不到.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigItem(
  val label: String,
  val description: String = "",
  val group: String = "",
  val widget: String = "",
  val placeholder: String = "",
)

/**
 * 标在 enum entry 上, 给前端提供可读的选项 label.
 * 同时声明 PROPERTY 与 FIELD 是因为 Kotlin enum entry 在编译产物里同时表现为静态字段与属性,
 * kotlinx.serialization descriptor 取注解时按字段读, 漏掉 FIELD 会拿不到.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigEnumEntry(
  val label: String,
)
