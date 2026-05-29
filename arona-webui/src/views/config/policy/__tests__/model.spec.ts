import { describe, it, expect } from "vitest";
import {
  addChildNode,
  findById,
  findParentToRoot,
  mapGraphToPolicy,
  mapPolicyToGraph,
  nextChildId,
  removeNode,
  ruleDisplayName,
  treeToFlow,
  updateNodeData,
  type GraphNode,
  type GraphRoot,
  type GraphRule,
} from "../model";
import { layoutWithDagre, NODE_H, NODE_W } from "../layout";
import { allFixtures, deepPolicy, nestedPolicy, simplePolicy } from "./fixtures";

describe("Policy ↔ Graph 映射", () => {
  it("mapPolicyToGraph 产出正确的根/层级与顺序(先 node 后 rule)", () => {
    const root = mapPolicyToGraph(nestedPolicy);
    expect(root.kind).toBe("policy-root");
    expect(root.id).toBe("policy.B");
    expect(root.effect).toBe("DENY");
    expect(root.children).toHaveLength(2);

    const first = root.children[0];
    expect(first.kind).toBe("policy-node");
    // ANY 节点: 1 个子 node + 2 条 rule, 顺序为 node 在前
    expect(first.children[0].kind).toBe("policy-node");
    expect(first.children.filter((c) => c.kind === "policy-rule")).toHaveLength(2);
  });

  it("roundtrip 业务等价且幂等(规范化后再回环不变)", () => {
    for (const p of allFixtures) {
      const normalized = mapGraphToPolicy(mapPolicyToGraph(p));
      // 关键业务字段保持
      expect(normalized.id).toBe(p.id);
      expect(normalized.name).toBe(p.name);
      expect(normalized.effect).toBe(p.effect);
      // 幂等: 规范化形态再走一遍应完全相等
      const again = mapGraphToPolicy(mapPolicyToGraph(normalized));
      expect(again).toEqual(normalized);
    }
  });

  it("数组值在回写策略时 join 成逗号字符串", () => {
    const root = mapPolicyToGraph(simplePolicy);
    const nodeId = root.children[0].id;
    addChildNode(root, nodeId, {
      kind: "policy-rule",
      oType: "Subject",
      operator: "IsIn",
      key: "id",
      value: ["a", "b", "c"],
    });
    const policy = mapGraphToPolicy(root);
    const added = policy.rules[0].rule?.find((r) => r.operator === "IsIn");
    expect(added?.value).toBe("a,b,c");
  });
});

describe("树查询", () => {
  it("findById 命中 root / node / rule, 未命中返回 undefined", () => {
    const root = mapPolicyToGraph(deepPolicy);
    expect(findById(root, root.id)).toBe(root);
    expect(findById(root, "policy.C.node1")?.kind).toBe("policy-node");
    expect(findById(root, "policy.C.node1.node1.node1.rule1")?.kind).toBe("policy-rule");
    expect(findById(root, "不存在")).toBeUndefined();
    expect(findById(root, "")).toBeUndefined();
  });

  it("findParentToRoot 返回 root→直接父 的祖先链(不含自身/不含 root 空 parent)", () => {
    const root = mapPolicyToGraph(deepPolicy);
    const leafRuleId = "policy.C.node1.node1.node1.rule1";
    expect(findParentToRoot(root, leafRuleId)).toEqual([
      "policy.C",
      "policy.C.node1",
      "policy.C.node1.node1",
      "policy.C.node1.node1.node1",
    ]);
  });
});

describe("nextChildId 防碰撞", () => {
  it("删除中间兄弟后新增不复用已删编号", () => {
    const root = mapPolicyToGraph(simplePolicy);
    const nodeId = root.children[0].id; // policy.A.node1
    const node = findById(root, nodeId) as GraphNode;
    // 当前已有 1 条 rule(.rule1)。再加两条 → .rule2 .rule3
    addChildNode(root, nodeId, { kind: "policy-rule", oType: "Resource", operator: "Equal", key: "id", value: "x" });
    addChildNode(root, nodeId, { kind: "policy-rule", oType: "Resource", operator: "Equal", key: "id", value: "y" });
    const ruleIds = node.children.filter((c) => c.kind === "policy-rule").map((c) => c.id);
    expect(ruleIds).toEqual([`${nodeId}.rule1`, `${nodeId}.rule2`, `${nodeId}.rule3`]);

    // 删除 .rule2, 再新增: 不得复用 .rule2, 应为 .rule4
    removeNode(root, `${nodeId}.rule2`);
    addChildNode(root, nodeId, { kind: "policy-rule", oType: "Resource", operator: "Equal", key: "id", value: "z" });
    const after = node.children.filter((c) => c.kind === "policy-rule").map((c) => c.id);
    expect(after).toEqual([`${nodeId}.rule1`, `${nodeId}.rule3`, `${nodeId}.rule4`]);
  });

  it("node 与 rule 编号互不干扰", () => {
    const root = mapPolicyToGraph(simplePolicy);
    const nodeId = root.children[0].id;
    expect(nextChildId(findById(root, nodeId) as GraphNode, "policy-node")).toBe(`${nodeId}.node1`);
    expect(nextChildId(findById(root, nodeId) as GraphNode, "policy-rule")).toBe(`${nodeId}.rule2`);
  });
});

describe("突变语义", () => {
  it("root/node 加 policy-node 子; rule 不能作父", () => {
    const root = mapPolicyToGraph(simplePolicy);
    addChildNode(root, root.id, { kind: "policy-node", groupType: "ANY" });
    expect(root.children).toHaveLength(2);
    expect(root.children[1].kind).toBe("policy-node");

    const ruleId = (root.children[0].children[0] as GraphRule).id;
    const before = JSON.stringify(root);
    addChildNode(root, ruleId, { kind: "policy-node", groupType: "ALL" }); // rule 作父 → no-op
    expect(JSON.stringify(root)).toBe(before);
  });

  it("updateNodeData 改 root/node/rule 字段", () => {
    const root = mapPolicyToGraph(simplePolicy);
    updateNodeData(root, root.id, { name: "改名", effect: "DENY" });
    expect(root.name).toBe("改名");
    expect(root.effect).toBe("DENY");

    const nodeId = root.children[0].id;
    updateNodeData(root, nodeId, { groupType: "NOT_ANY" });
    expect((findById(root, nodeId) as GraphNode).groupType).toBe("NOT_ANY");

    const ruleId = (root.children[0].children[0] as GraphRule).id;
    updateNodeData(root, ruleId, { operator: "IsIn", value: "v2" });
    const rule = findById(root, ruleId) as GraphRule;
    expect(rule.operator).toBe("IsIn");
    expect(rule.value).toBe("v2");
  });

  it("removeNode 删子树且清理 collapsed 残留; root 不可删", () => {
    const root = mapPolicyToGraph(deepPolicy);
    const collapsed = new Set<string>(["policy.C.node1.node1", "policy.C.node1.node1.node1"]);
    removeNode(root, "policy.C.node1.node1", collapsed);
    expect(findById(root, "policy.C.node1.node1")).toBeUndefined();
    expect(findById(root, "policy.C.node1.node1.node1")).toBeUndefined();
    // 被删子树的 id 应从 collapsed 清除
    expect(collapsed.has("policy.C.node1.node1")).toBe(false);
    expect(collapsed.has("policy.C.node1.node1.node1")).toBe(false);

    const snapshot = JSON.stringify(root);
    removeNode(root, root.id); // root no-op
    expect(JSON.stringify(root)).toBe(snapshot);
  });
});

describe("treeToFlow", () => {
  it("全展开: 节点数=树节点数, 边数=节点数-1, 携带正确 data", () => {
    const root = mapPolicyToGraph(deepPolicy);
    const { nodes, edges } = treeToFlow(root);
    expect(nodes).toHaveLength(7);
    expect(edges).toHaveLength(6);
    const rootNode = nodes.find((n) => n.id === root.id)!;
    expect(rootNode.type).toBe("policy-root");
    expect(rootNode.data!.hasChildren).toBe(true);
    expect(rootNode.data!.collapsed).toBe(false);
  });

  it("折叠节点自身可见, 后代隐藏, 不产生 orphan edge", () => {
    const root = mapPolicyToGraph(deepPolicy);
    const collapsedId = "policy.C.node1.node1";
    const { nodes, edges } = treeToFlow(root, { collapsed: new Set([collapsedId]) });

    const ids = new Set(nodes.map((n) => n.id));
    expect(ids.has(collapsedId)).toBe(true); // 折叠节点自身可见
    expect(nodes.find((n) => n.id === collapsedId)!.data!.collapsed).toBe(true);
    expect(ids.has("policy.C.node1.node1.node1")).toBe(false); // 后代隐藏
    expect(ids.has("policy.C.node1.node1.node1.rule1")).toBe(false);
    expect(ids.has("policy.C.node1.node1.rule1")).toBe(false);

    // 所有边的两端都必须在可见集合内
    edges.forEach((e) => {
      expect(ids.has(e.source)).toBe(true);
      expect(ids.has(e.target)).toBe(true);
    });
    // 折叠节点不应有出边
    expect(edges.some((e) => e.source === collapsedId)).toBe(false);
  });

  it("status 经 statusById 注入到对应节点 data", () => {
    const root = mapPolicyToGraph(simplePolicy);
    const { nodes } = treeToFlow(root, { statusById: new Map([[root.id, "deny"]]) });
    expect(nodes.find((n) => n.id === root.id)!.data!.status).toBe("deny");
  });

  it("快照的数组 value 与图树隔离(改快照不污染树)", () => {
    const root = mapPolicyToGraph(simplePolicy);
    const nodeId = root.children[0].id;
    addChildNode(root, nodeId, { kind: "policy-rule", oType: "Subject", operator: "IsIn", key: "id", value: ["a"] });
    const ruleNode = treeToFlow(root).nodes.find(
      (n) => n.data!.kind === "policy-rule" && Array.isArray((n.data!.snapshot as { value: unknown }).value),
    )!;
    const snap = ruleNode.data!.snapshot as { value: string[] };
    snap.value.push("hacked");
    const policy = mapGraphToPolicy(root);
    const ruleValues = policy.rules[0].rule?.map((r) => r.value) ?? [];
    expect(ruleValues).not.toContain("a,hacked");
  });

  it("空策略(单根无子): 1 节点 0 边", () => {
    const root = mapPolicyToGraph({ id: "p.empty", name: "空", effect: "ALLOW", rules: [] });
    const { nodes, edges } = treeToFlow(root);
    expect(nodes).toHaveLength(1);
    expect(edges).toHaveLength(0);
    expect(nodes[0].data!.hasChildren).toBe(false);
  });
});

describe("ruleDisplayName", () => {
  it("拼接 [oType.key] operator\\n[value], 数组 join", () => {
    expect(ruleDisplayName({ oType: "Subject", key: "roles", operator: "ContainsAll", value: ["a", "b"] })).toBe(
      "[Subject.roles] ContainsAll\n[a,b]",
    );
  });
});

describe("layoutWithDagre", () => {
  it("为每个节点写入数值 position, 不 mutate 入参", () => {
    const root = mapPolicyToGraph(nestedPolicy);
    const { nodes, edges } = treeToFlow(root);
    const laid = layoutWithDagre(nodes, edges);

    expect(laid).toHaveLength(nodes.length);
    laid.forEach((n) => {
      expect(Number.isFinite(n.position.x)).toBe(true);
      expect(Number.isFinite(n.position.y)).toBe(true);
    });
    // 入参未被 mutate(仍是初始 0,0)
    expect(nodes.every((n) => n.position.x === 0 && n.position.y === 0)).toBe(true);
    // 常量导出正确
    expect([NODE_W, NODE_H]).toEqual([202, 60]);
    // LR 布局: root 应在最左(x 最小)
    const rootNode = laid.find((n) => n.id === root.id)!;
    const minX = Math.min(...laid.map((n) => n.position.x));
    expect(rootNode.position.x).toBe(minX);
  });
});
