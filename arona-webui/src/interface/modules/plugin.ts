export interface PluginPreference {
  id: string; // 插件id
  key: string; // 配置文件名
  value: Record<string, unknown>; // 配置 JSON 对象, 与后端 BSON 子文档结构一一对应
}

type PluginPreferences = Record<string, PluginPreference>;
