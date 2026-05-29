import type { Policy } from "@/interface";

// 数据层测试用的固定策略样本 (domain 形态, 即后端 read/save 的形状).
// 经 mapPolicyToGraph 派生图树, 再经 mapGraphToPolicy 回写, 应与原始业务语义等价.

// A: 最简 —— 单 node 单 rule
export const simplePolicy: Policy = {
  id: "policy.A",
  name: "简单策略",
  effect: "ALLOW",
  rules: [
    {
      groupType: "ALL",
      rule: [{ type: "Resource", operator: "Equal", key: "id", value: "res-1" }],
    },
  ],
};

// B: 嵌套 —— node 下挂子 node + 多 rule (含 NOT_* 与数组语义 operator)
export const nestedPolicy: Policy = {
  id: "policy.B",
  name: "嵌套策略",
  effect: "DENY",
  rules: [
    {
      groupType: "ANY",
      rule: [
        { type: "Subject", operator: "Contains", key: "roles", value: "role.admin" },
        { type: "Environment", operator: "GreaterThan", key: "time", value: "09:00:00" },
      ],
      children: [
        {
          groupType: "NOT_ALL",
          rule: [{ type: "Subject", operator: "IsIn", key: "id", value: "u1,u2,u3" }],
        },
      ],
    },
    {
      groupType: "ALL",
      rule: [{ type: "Resource", operator: "IsChild", key: "id", value: "group-x" }],
    },
  ],
};

// C: 较深 —— 三层 group 嵌套, 用于折叠过滤 / dagre 布局
export const deepPolicy: Policy = {
  id: "policy.C",
  name: "深层策略",
  effect: "ALLOW",
  rules: [
    {
      groupType: "ALL",
      children: [
        {
          groupType: "ANY",
          children: [
            {
              groupType: "ALL",
              rule: [{ type: "Resource", operator: "Equal", key: "id", value: "leaf" }],
            },
          ],
          rule: [{ type: "Action", operator: "Equal", key: "id", value: "read" }],
        },
      ],
      rule: [{ type: "Subject", operator: "Equal", key: "id", value: "owner" }],
    },
  ],
};

export const allFixtures: Policy[] = [simplePolicy, nestedPolicy, deepPolicy];
