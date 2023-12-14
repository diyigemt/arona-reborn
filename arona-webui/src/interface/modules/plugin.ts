export interface PluginPreference {
  id: string; // 插件id
  key: string; // 配置文件名
  value: string; // 配置文件json序列化后的值
}

type PluginPreferences = Record<string, PluginPreference>;
