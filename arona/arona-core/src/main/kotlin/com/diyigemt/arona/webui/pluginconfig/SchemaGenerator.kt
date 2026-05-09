package com.diyigemt.arona.webui.pluginconfig

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 插件配置 schema 顶层结构, 与前端 [PluginConfigSchema] TS 接口一一对应. */
@Serializable
data class PluginConfigSchema(
  val pluginId: String,
  val configKey: String,
  val fields: List<ConfigFieldSchema>,
)

/**
 * 单个字段 schema. 字段类型 [type] 取值见 [SchemaGenerator.schemaType]:
 * `boolean` / `integer` / `number` / `string` / `enum` / `array` / `map` / `object` / `polymorphic` / `unknown`.
 */
@Serializable
data class ConfigFieldSchema(
  val key: String,
  val type: String,
  val label: String,
  val description: String = "",
  val group: String = "",
  val widget: String = "",
  val placeholder: String = "",
  val nullable: Boolean = false,
  val optional: Boolean = false,
  val defaultValue: JsonElement? = null,
  val enumOptions: List<ConfigEnumOptionSchema> = emptyList(),
  val itemSchema: ConfigFieldSchema? = null,
  val fields: List<ConfigFieldSchema> = emptyList(),
)

@Serializable
data class ConfigEnumOptionSchema(
  val value: String,
  val label: String,
)

/**
 * 走 [SerialDescriptor] 生成 [PluginConfigSchema]. 仅依赖 kotlinx.serialization 的描述符 API,
 * 不做反射. [ConfigItem]/[ConfigEnumEntry] 通过 [SerialInfo] 附在 descriptor 上由 getElementAnnotations 取出.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SchemaGenerator {

  fun generate(
    pluginId: String,
    configKey: String,
    serializer: KSerializer<*>,
    defaultJson: JsonElement?,
  ): PluginConfigSchema {
    val descriptor = serializer.descriptor
    val visited = linkedSetOf(descriptor.serialName)
    val fields = buildObjectFields(descriptor, defaultJson, visited)
    return PluginConfigSchema(pluginId = pluginId, configKey = configKey, fields = fields)
  }

  private fun buildField(
    key: String,
    descriptor: SerialDescriptor,
    annotations: List<Annotation>,
    nullable: Boolean,
    optional: Boolean,
    defaultValue: JsonElement?,
    visited: LinkedHashSet<String>,
  ): ConfigFieldSchema {
    val item = annotations.filterIsInstance<ConfigItem>().firstOrNull()
    val base = ConfigFieldSchema(
      key = key,
      type = schemaType(descriptor.kind),
      label = item?.label?.takeIf { it.isNotBlank() } ?: key,
      description = item?.description.orEmpty(),
      group = item?.group.orEmpty(),
      widget = item?.widget.orEmpty(),
      placeholder = item?.placeholder.orEmpty(),
      nullable = nullable,
      optional = optional,
      defaultValue = defaultValue,
    )
    return when (descriptor.kind) {
      is PrimitiveKind -> base
      SerialKind.ENUM -> base.copy(enumOptions = buildEnumOptions(descriptor))
      StructureKind.LIST -> base.copy(itemSchema = buildListItemSchema(descriptor, visited))
      StructureKind.MAP -> base.copy(itemSchema = buildMapValueSchema(descriptor, visited))
      StructureKind.CLASS, StructureKind.OBJECT -> {
        val serialName = descriptor.serialName
        if (!visited.add(serialName)) {
          // 循环引用: 终止递归避免栈溢出, 前端按 widget 提示降级.
          return base.copy(widget = base.widget.ifBlank { "recursive-reference" })
        }
        try {
          base.copy(fields = buildObjectFields(descriptor, defaultValue, visited))
        } finally {
          visited.remove(serialName)
        }
      }
      PolymorphicKind.SEALED, PolymorphicKind.OPEN -> base.copy(
        widget = base.widget.ifBlank { "polymorphic" },
      )
      else -> base.copy(widget = base.widget.ifBlank { "unsupported" })
    }
  }

  private fun buildObjectFields(
    descriptor: SerialDescriptor,
    defaultJson: JsonElement?,
    visited: LinkedHashSet<String>,
  ): List<ConfigFieldSchema> {
    val defaultObject = defaultJson as? JsonObject
    return List(descriptor.elementsCount) { index ->
      val name = descriptor.getElementName(index)
      val elementDescriptor = descriptor.getElementDescriptor(index)
      buildField(
        key = name,
        descriptor = elementDescriptor,
        annotations = descriptor.getElementAnnotations(index),
        nullable = elementDescriptor.isNullable,
        optional = descriptor.isElementOptional(index),
        defaultValue = defaultObject?.get(name),
        visited = visited,
      )
    }
  }

  private fun buildListItemSchema(
    descriptor: SerialDescriptor,
    visited: LinkedHashSet<String>,
  ): ConfigFieldSchema? {
    if (descriptor.elementsCount == 0) return null
    val itemDescriptor = descriptor.getElementDescriptor(0)
    return buildField(
      key = "item",
      descriptor = itemDescriptor,
      annotations = emptyList(),
      nullable = itemDescriptor.isNullable,
      optional = false,
      defaultValue = null,
      visited = visited,
    )
  }

  private fun buildMapValueSchema(
    descriptor: SerialDescriptor,
    visited: LinkedHashSet<String>,
  ): ConfigFieldSchema? {
    // Map 的 descriptor 第 0 个元素是 key, 第 1 个是 value, Phase 0 仅描述 value 形态.
    if (descriptor.elementsCount < 2) return null
    val valueDescriptor = descriptor.getElementDescriptor(1)
    return buildField(
      key = "value",
      descriptor = valueDescriptor,
      annotations = emptyList(),
      nullable = valueDescriptor.isNullable,
      optional = false,
      defaultValue = null,
      visited = visited,
    )
  }

  private fun buildEnumOptions(descriptor: SerialDescriptor): List<ConfigEnumOptionSchema> {
    return List(descriptor.elementsCount) { index ->
      val value = descriptor.getElementName(index)
      val label = descriptor.getElementAnnotations(index)
        .filterIsInstance<ConfigEnumEntry>()
        .firstOrNull()
        ?.label
        ?.takeIf { it.isNotBlank() }
        ?: value
      ConfigEnumOptionSchema(value = value, label = label)
    }
  }

  private fun schemaType(kind: SerialKind): String = when (kind) {
    PrimitiveKind.BOOLEAN -> "boolean"
    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
    PrimitiveKind.CHAR, PrimitiveKind.STRING -> "string"
    SerialKind.ENUM -> "enum"
    StructureKind.LIST -> "array"
    StructureKind.MAP -> "map"
    StructureKind.CLASS, StructureKind.OBJECT -> "object"
    PolymorphicKind.SEALED, PolymorphicKind.OPEN -> "polymorphic"
    else -> "unknown"
  }
}
