import type { InjectionKey } from "vue";

// 节点内嵌按钮的动作契约：节点组件 inject 调用，UserPolicy provide 实现（开 dialog / 改图数据）。
// 经 provide/inject 解耦，避免节点组件直接依赖父组件或全局事件总线。
export interface PolicyNodeActions {
  edit(id: string): void;
  append(id: string): void;
  remove(id: string): void;
  toggleCollapse(id: string): void;
}

export const POLICY_NODE_ACTIONS: InjectionKey<PolicyNodeActions> = Symbol("POLICY_NODE_ACTIONS");

// inject 兜底：未提供时为 no-op，避免组件在隔离渲染（如 storybook/测试）时报错。
export const noopPolicyNodeActions: PolicyNodeActions = {
  edit: () => undefined,
  append: () => undefined,
  remove: () => undefined,
  toggleCollapse: () => undefined,
};
