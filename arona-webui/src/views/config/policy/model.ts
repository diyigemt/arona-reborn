import { MarkerType, type Edge, type Node, type Position } from "@vue-flow/core";
import type {
  Policy,
  PolicyNode,
  PolicyNodeGroupType,
  PolicyRootEffect,
  PolicyRule,
  PolicyRuleOperator,
  PolicyRuleType,
} from "@/interface";

// ──────────────────────────────────────────────────────────────────────────
// 策略图数据层（纯函数，零运行时 UI 依赖）
//
// 设计：嵌套树 GraphRoot 是唯一可写真源，onSave 经 mapGraphToPolicy 读它；
// treeToFlow 把树派生为 Vue Flow 扁平 nodes/edges（视图），layout.ts 负责坐标。
// 所有突变（add/update/remove）就地作用于嵌套树，调用方随后 rebuild 派生视图。
//
// 判别式用 kind（图节点形态），与业务对象类型 oType 分离——消除旧实现中 type
// 字段双重含义导致的 @ts-ignore。
//
// 注：@vue-flow/core 仅作 **类型** 引入（`import type`，构建期擦除），本模块在运行时
// 不依赖渲染库，单测可在 node 环境直接加载、无副作用。
// ──────────────────────────────────────────────────────────────────────────

export type GraphKind = "policy-root" | "policy-node" | "policy-rule";

export interface GraphRule {
  id: string;
  kind: "policy-rule";
  parent: string;
  oType: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  // 表单期临时可为 string[]（ContainsAll/IsIn 等多值操作符）；save 时 join 成 string。
  value: string | string[];
}

export interface GraphNode {
  id: string;
  kind: "policy-node";
  parent: string;
  groupType: PolicyNodeGroupType;
  children: GraphTreeNode[];
}

export interface GraphRoot {
  id: string;
  kind: "policy-root";
  parent: "";
  name: string;
  effect: PolicyRootEffect;
  children: GraphNode[];
}

export type GraphTreeNode = GraphNode | GraphRule;
export type GraphAnyNode = GraphRoot | GraphNode | GraphRule;
/** 仅有 children 的容器节点（root / node）——可作为新增子节点的父。 */
export type GraphParentNode = GraphRoot | GraphNode;

/** 兼容旧导入名（迁移期 util.ts 再 re-export）。 */
export type GraphPolicyRoot = GraphRoot;

export type PolicyNodeStatus = "allow" | "deny";

// ── Vue Flow 节点 data 载荷（只读快照，组件不得改之，改动只走数据层函数）──
export interface RootSnapshot {
  name: string;
  effect: PolicyRootEffect;
}
export interface NodeSnapshot {
  groupType: PolicyNodeGroupType;
}
export interface RuleSnapshot {
  oType: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  value: string | string[];
  /** 预拼接的展示名，供 tooltip。 */
  name: string;
}
export type GraphSnapshot = RootSnapshot | NodeSnapshot | RuleSnapshot;

export interface GraphFlowData {
  kind: GraphKind;
  snapshot: Readonly<GraphSnapshot>;
  /** 有子节点才显示折叠按钮。 */
  hasChildren: boolean;
  collapsed: boolean;
  status?: PolicyNodeStatus;
}

export type PolicyFlowNode = Node<GraphFlowData>;

type AddChildPayload =
  | { kind: "policy-node"; groupType: PolicyNodeGroupType }
  | { kind: "policy-rule"; oType: PolicyRuleType; operator: PolicyRuleOperator; key: string; value: string | string[] };

export type UpdateNodePatch =
  | Partial<RootSnapshot>
  | Partial<NodeSnapshot>
  | Partial<Pick<GraphRule, "oType" | "operator" | "key" | "value">>;

// ── Vue Flow Position 枚举（字符串枚举）。这里只作类型引入以保持数据层运行时纯净，
//    故用编译期断言把字面量收敛成枚举类型；运行时即普通字符串 "right"/"left"。──
const SOURCE_POSITION = "right" as unknown as Position;
const TARGET_POSITION = "left" as unknown as Position;

const EDGE_STROKE = "#a0cfff";

// ──────────────────────────────────────────────────────────────────────────
// Policy(业务) ↔ Graph(图树) 映射
// ──────────────────────────────────────────────────────────────────────────

function mapPolicyRuleToGraph(r: PolicyRule, parent: GraphNode): GraphRule {
  return {
    id: nextChildId(parent, "policy-rule"),
    kind: "policy-rule",
    parent: parent.id,
    oType: r.type,
    operator: r.operator,
    key: r.key,
    value: r.value,
  };
}

function mapPolicyNodeToGraph(r: PolicyNode, parent: GraphParentNode): GraphNode {
  const node: GraphNode = {
    id: nextChildId(parent, "policy-node"),
    kind: "policy-node",
    parent: parent.id,
    groupType: r.groupType,
    children: [],
  };
  // 沿用旧顺序语义：先子 node，后 rule。
  (r.children || []).forEach((it) => node.children.push(mapPolicyNodeToGraph(it, node)));
  (r.rule || []).forEach((it) => node.children.push(mapPolicyRuleToGraph(it, node)));
  return node;
}

export function mapPolicyToGraph(p: Policy): GraphRoot {
  // 深拷贝隔离，避免突变图树时污染上游 policy 对象。
  const copy = JSON.parse(JSON.stringify(p)) as Policy;
  const root: GraphRoot = {
    id: copy.id,
    kind: "policy-root",
    parent: "",
    name: copy.name,
    effect: copy.effect,
    children: [],
  };
  (copy.rules || []).forEach((it) => root.children.push(mapPolicyNodeToGraph(it, root)));
  return root;
}

function mapGraphRuleToPolicyRule(rule: GraphRule): PolicyRule {
  return {
    type: rule.oType,
    operator: rule.operator,
    key: rule.key,
    // 多值操作符的数组在持久化时 join 成 string（保持旧行为）。
    value: Array.isArray(rule.value) ? rule.value.join(",") : rule.value,
  };
}

function mapGraphNodeToPolicyNode(node: GraphNode): PolicyNode {
  return {
    groupType: node.groupType,
    rule: node.children.filter((it): it is GraphRule => it.kind === "policy-rule").map(mapGraphRuleToPolicyRule),
    children: node.children.filter((it): it is GraphNode => it.kind === "policy-node").map(mapGraphNodeToPolicyNode),
  };
}

export function mapGraphToPolicy(root: GraphRoot): Policy {
  return {
    id: root.id,
    name: root.name,
    effect: root.effect,
    rules: root.children.map(mapGraphNodeToPolicyNode),
  };
}

// ──────────────────────────────────────────────────────────────────────────
// 树查询
// ──────────────────────────────────────────────────────────────────────────

function childrenOf(node: GraphAnyNode): readonly GraphTreeNode[] {
  return node.kind === "policy-rule" ? [] : node.children;
}

export function findById(root: GraphRoot, id: string): GraphAnyNode | undefined {
  if (!id) return undefined;
  if (root.id === id) return root;
  for (const child of root.children) {
    const hit = findInSubtree(child, id);
    if (hit) return hit;
  }
  return undefined;
}

function findInSubtree(node: GraphTreeNode, id: string): GraphAnyNode | undefined {
  if (node.id === id) return node;
  if (node.kind === "policy-node") {
    for (const child of node.children) {
      const hit = findInSubtree(child, id);
      if (hit) return hit;
    }
  }
  return undefined;
}

/**
 * 返回从 root 一侧到目标节点「直接父」为止的祖先 id 列表（不含目标自身、不含 root 的空 parent）。
 * 复刻旧 G6 helper 语义，但基于嵌套树。
 */
export function findParentToRoot(root: GraphRoot, id: string): string[] {
  if (!id) return [];
  const item = findById(root, id);
  if (!item) return [];
  return [...findParentToRoot(root, item.parent), item.parent].filter((it) => it);
}

// ──────────────────────────────────────────────────────────────────────────
// 突变（就地作用于嵌套树）
// ──────────────────────────────────────────────────────────────────────────

/**
 * 生成同类子节点的下一个 id。
 * 修复旧实现 `children.length + 1` 的碰撞 bug：从 [.node1, .node3] 删掉 .node2 后，
 * length+1 会再次产出 .node3。这里扫描同类兄弟已用的最大数字后缀再 +1，保证唯一。
 */
export function nextChildId(parent: GraphParentNode, kind: "policy-node" | "policy-rule"): string {
  const suffix = kind === "policy-node" ? "node" : "rule";
  const re = new RegExp(`\\.${suffix}(\\d+)$`);
  const used = parent.children
    .filter((it) => it.kind === kind)
    .map((it) => {
      const m = it.id.match(re);
      return m ? Number(m[1]) : 0;
    });
  const next = Math.max(0, ...used) + 1;
  return `${parent.id}.${suffix}${next}`;
}

/**
 * 在 parentId 节点下追加子节点。
 * - root / policy-node 可加 policy-node 子；
 * - policy-rule **不能** 有子：UI 上「rule 点 +」的语义是给该 rule 的父节点追加 sibling rule，
 *   故调用方应传 rule 的 parent id 作为 parentId（见 UserPolicy 接线）。
 * 就地 mutate 并返回 root 以便链式使用。
 */
export function addChildNode(root: GraphRoot, parentId: string, payload: AddChildPayload): GraphRoot {
  const parent = findById(root, parentId);
  if (!parent || parent.kind === "policy-rule") return root;

  if (payload.kind === "policy-node") {
    parent.children.push({
      id: nextChildId(parent, "policy-node"),
      kind: "policy-node",
      parent: parent.id,
      groupType: payload.groupType,
      children: [],
    });
    return root;
  }

  // rule 只能挂在 policy-node 下。
  if (parent.kind !== "policy-node") return root;
  parent.children.push({
    id: nextChildId(parent, "policy-rule"),
    kind: "policy-rule",
    parent: parent.id,
    oType: payload.oType,
    operator: payload.operator,
    key: payload.key,
    value: payload.value,
  });
  return root;
}

export function updateNodeData(root: GraphRoot, id: string, patch: UpdateNodePatch): void {
  const node = findById(root, id);
  if (!node) return;

  if (node.kind === "policy-root") {
    const p = patch as Partial<RootSnapshot>;
    if (p.name !== undefined) node.name = p.name;
    if (p.effect !== undefined) node.effect = p.effect;
    return;
  }
  if (node.kind === "policy-node") {
    const p = patch as Partial<NodeSnapshot>;
    if (p.groupType !== undefined) node.groupType = p.groupType;
    return;
  }
  const p = patch as Partial<Pick<GraphRule, "oType" | "operator" | "key" | "value">>;
  if (p.oType !== undefined) node.oType = p.oType;
  if (p.operator !== undefined) node.operator = p.operator;
  if (p.key !== undefined) node.key = p.key;
  if (p.value !== undefined) node.value = p.value;
}

function collectSubtreeIds(node: GraphTreeNode, out: Set<string>): void {
  out.add(node.id);
  if (node.kind === "policy-node") node.children.forEach((c) => collectSubtreeIds(c, out));
}

/**
 * 删除 id 对应的子树（root 不可删）。同时清理被删子树残留在 collapsed 集合中的 id。
 */
export function removeNode(root: GraphRoot, id: string, collapsed?: Set<string>): void {
  if (!id || id === root.id) return;
  const target = findById(root, id);
  if (!target || target.kind === "policy-root") return;

  const parent = findById(root, target.parent);
  if (!parent || parent.kind === "policy-rule") return;

  // 按 parent 具体类型分别回写，避免联合类型赋值报错。
  if (parent.kind === "policy-root") {
    parent.children = parent.children.filter((it) => it.id !== id) as GraphNode[];
  } else {
    parent.children = parent.children.filter((it) => it.id !== id);
  }

  if (collapsed) {
    const removed = new Set<string>();
    collectSubtreeIds(target as GraphTreeNode, removed);
    removed.forEach((rid) => collapsed.delete(rid));
  }
}

// ──────────────────────────────────────────────────────────────────────────
// 展示
// ──────────────────────────────────────────────────────────────────────────

/** 规则节点展示名：`[oType.key] operator\n[value]`，供 tooltip / 旧 G6 一致。 */
export function ruleDisplayName(r: Pick<GraphRule, "oType" | "key" | "operator" | "value">): string {
  const value = Array.isArray(r.value) ? r.value.join(",") : r.value;
  return `[${r.oType}.${r.key}] ${r.operator}\n[${value}]`;
}

function snapshotOf(node: GraphAnyNode): GraphSnapshot {
  switch (node.kind) {
    case "policy-root":
      return { name: node.name, effect: node.effect };
    case "policy-node":
      return { groupType: node.groupType };
    case "policy-rule":
      return {
        oType: node.oType,
        operator: node.operator,
        key: node.key,
        // 拷贝数组: 快照标称只读, 防组件侧误改 value 数组反向污染图树。
        value: Array.isArray(node.value) ? [...node.value] : node.value,
        name: ruleDisplayName(node),
      };
  }
}

// ──────────────────────────────────────────────────────────────────────────
// 树 → Vue Flow 扁平视图
// ──────────────────────────────────────────────────────────────────────────

/**
 * 单次 DFS 同时产出 nodes 和 edges。
 * - 折叠节点自身仍可见，但 collapsed.has(id) 时不递归其后代；
 * - 边只在「可见 parent → 可见 child」之间生成（在递归进入子节点前建边），杜绝 orphan edge；
 * - position 先置 (0,0)，由 layoutWithDagre 填入。
 */
export function treeToFlow(
  root: GraphRoot,
  opts: { collapsed?: Set<string>; statusById?: Map<string, PolicyNodeStatus> } = {},
): { nodes: PolicyFlowNode[]; edges: Edge[] } {
  const { collapsed = new Set<string>(), statusById } = opts;
  const nodes: PolicyFlowNode[] = [];
  const edges: Edge[] = [];

  const visit = (node: GraphAnyNode, visibleParentId?: string): void => {
    const children = childrenOf(node);
    const isCollapsed = collapsed.has(node.id);

    nodes.push({
      id: node.id,
      type: node.kind,
      position: { x: 0, y: 0 },
      sourcePosition: SOURCE_POSITION,
      targetPosition: TARGET_POSITION,
      data: {
        kind: node.kind,
        snapshot: snapshotOf(node),
        hasChildren: children.length > 0,
        collapsed: isCollapsed,
        status: statusById?.get(node.id),
      },
    });

    if (visibleParentId) {
      edges.push({
        id: `e:${visibleParentId}->${node.id}`,
        source: visibleParentId,
        target: node.id,
        type: "step",
        style: { stroke: EDGE_STROKE },
        markerEnd: {
          type: MarkerType.Arrow,
          color: "transparent",
        },
      });
    }

    if (isCollapsed) return;
    children.forEach((child) => visit(child, node.id));
  };

  visit(root);
  return { nodes, edges };
}
