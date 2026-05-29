import { FormItemRule } from "element-plus";
import { PolicyRuleType } from "@/interface";

// 图数据层(类型 / Policy↔Graph 映射 / 树查询)已迁至 ./model。
// 这里 re-export 以兼容历史导入路径 ("@/views/config/policy/util")。
export { findParentToRoot, mapGraphToPolicy, mapPolicyToGraph } from "./model";
export type { GraphPolicyRoot } from "./model";

export const PolicyRuleFormRule: { [key: string]: FormItemRule } = {
  type: {
    required: true,
  },
  key: {
    required: true,
  },
  operator: {
    required: true,
  },
  value: {
    required: true,
  },
};

// --- 策略测试表单结构 (被 PolicyTestDataBuilder.vue 和 UserPolicy.vue 共用) ---
// 实际求值由后端 /policy/preview endpoint 处理, 此处仅保留输入表单类型.

export interface Resource {
  id: string;
}

export interface Action {
  action: string;
}

export interface Subject {
  id: string;
  roles: string[];
}

export interface Environment {
  time: string;
  date: string;
  datetime: string;
  param1: string;
  param2: string;
}

export type PolicyTestResultStatus = "allow" | "deny";

export type PolicyInput = {
  [key in PolicyRuleType]: unknown;
};

export interface PolicyTestInput extends PolicyInput {
  Resource: Resource;
  Action: Action;
  Subject: Subject;
  Environment: Environment;
}
