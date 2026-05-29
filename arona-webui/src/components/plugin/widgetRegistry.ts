import type { Component } from "vue";

/**
 * 自定义字段渲染组件本地注册表. 后端 schema 仅提供语义层 widget 标签 (如 "gacha-pool"),
 * 真实的 Vue 组件由前端在启动时 register 进来, 解耦后端与前端组件名.
 *
 * 查找 key 形如 `${pluginId}/${configKey}/${fieldPath}`. fieldPath 用 "." 拼接, 例如
 * "pools.0.rate.ssr"; 顶层字段 path 即字段 key.
 */
type WidgetKey = string;

const registry = new Map<WidgetKey, Component>();

function buildKey(pluginId: string, configKey: string, fieldPath: string): WidgetKey {
  return `${pluginId}/${configKey}/${fieldPath}`;
}

export function registerWidget(pluginId: string, configKey: string, fieldPath: string, component: Component): void {
  registry.set(buildKey(pluginId, configKey, fieldPath), component);
}

export function resolveWidget(pluginId: string, configKey: string, fieldPath: string): Component | undefined {
  return registry.get(buildKey(pluginId, configKey, fieldPath));
}
