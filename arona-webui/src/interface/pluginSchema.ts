/**
 * 后端 PluginConfigSchema 的 TS 形态. 与 arona-core SchemaGenerator.kt 中的
 * `data class ConfigFieldSchema` / `PluginConfigSchema` 一一对应.
 */
export interface ConfigEnumOptionSchema {
  value: string;
  label: string;
}

export type ConfigFieldType =
  | "boolean"
  | "integer"
  | "number"
  | "string"
  | "enum"
  | "array"
  | "map"
  | "object"
  | "polymorphic"
  | "unknown";

export interface ConfigFieldSchema {
  key: string;
  type: ConfigFieldType;
  label: string;
  description?: string;
  group?: string;
  widget?: string;
  placeholder?: string;
  nullable?: boolean;
  optional?: boolean;
  // 后端用 JsonElement 表达, 实际可能是 boolean / number / string / object / array / null.
  defaultValue?: unknown;
  enumOptions?: ConfigEnumOptionSchema[];
  itemSchema?: ConfigFieldSchema;
  fields?: ConfigFieldSchema[];
}

export interface PluginConfigSchema {
  pluginId: string;
  configKey: string;
  fields: ConfigFieldSchema[];
}

export interface FieldError {
  path: string;
  message: string;
}

/** 后端 errorMessage(message, fieldErrors) 走 ServerResponse.data 透出的形态. */
export interface FieldErrorPayload {
  fieldErrors: FieldError[];
}

/** simplifiedApiService 在业务失败时 reject 的统一错误对象. */
export interface BusinessError {
  message: string;
  fieldErrors?: FieldError[];
}
