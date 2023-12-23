import { TreeGraph } from "@antv/g6";
import { FormItemRule } from "element-plus";
import { Policy, PolicyNode, PolicyRule, PolicyRuleType } from "@/interface";

interface GraphDataBase {
  id: string;
  type: "policy-root" | "policy-node" | "policy-rule";
  parent: string;
}
// @ts-ignore
interface GraphPolicyRule extends PolicyRule, GraphDataBase {
  type: "policy-rule";
  oType: PolicyRuleType;
}
// @ts-ignore
interface GraphPolicyNode extends PolicyNode, GraphDataBase {
  children?: (GraphPolicyNode | GraphPolicyRule)[];
}
export interface GraphPolicyRoot extends Policy, GraphDataBase {
  children: GraphPolicyNode[];
}
function mapPolicyRuleToGraph(r: PolicyRule, parent: GraphPolicyNode): GraphPolicyRule {
  const index = (parent.children?.length || 0) + 1;
  return {
    id: `${parent.id}.rule${index}`,
    parent: parent.id,
    type: "policy-rule",
    oType: r.type,
    key: r.key,
    value: r.value,
    operator: r.operator,
  };
}
function mapPolicyNodeToGraph(r: PolicyNode, parent: GraphPolicyRoot | GraphPolicyNode): GraphPolicyNode {
  const index = (parent.children?.length || 0) + 1;
  const node: GraphPolicyNode = {
    id: `${parent.id}.node${index}`,
    parent: parent.id,
    type: "policy-node",
    groupType: r.groupType,
    children: [],
  };
  (r.children || []).forEach((it) => {
    node.children?.push(mapPolicyNodeToGraph(it, node));
  });
  (r.rule || []).forEach((it) => {
    node.children?.push(mapPolicyRuleToGraph(it, node));
  });
  return node;
}
export function mapPolicyToGraph(p: Policy): GraphPolicyRoot {
  const copy = JSON.parse(JSON.stringify(p)) as GraphPolicyRoot;
  copy.type = "policy-root";
  copy.children = [];
  (copy.rules || []).forEach((it) => {
    copy.children.push(mapPolicyNodeToGraph(it, copy));
  });
  return copy;
}
function mapGraphRuleToPolicyRule(node: GraphPolicyRule): PolicyRule {
  return {
    type: node.oType,
    operator: node.operator,
    key: node.key,
    value: node.value,
  };
}
function mapGraphNodeToPolicyNode(node: GraphPolicyNode): PolicyNode {
  const data: PolicyNode = {
    groupType: node.groupType,
  };
  if (node.children) {
    data.rule = node.children
      .filter((it) => it.type === "policy-rule")
      .map((it) => mapGraphRuleToPolicyRule(it as GraphPolicyRule));
    data.children = node.children
      .filter((it) => it.type === "policy-node")
      .map((it) => mapGraphNodeToPolicyNode(it as GraphPolicyNode));
  }
  return data;
}
export function mapGraphToPolicy(root: GraphPolicyRoot): Policy {
  return {
    id: root.id,
    name: root.name,
    effect: root.effect,
    rules: root.children.map((it) => mapGraphNodeToPolicyNode(it)),
  };
}
export function findParentToRoot(g: TreeGraph, id: string): string[] {
  if (!id) {
    return [];
  }
  const item = g.findById(id);
  if (item) {
    const model = item.getModel();
    return [...findParentToRoot(g, model.parent as string), model.parent as string].filter((it) => it);
  }
  return [];
}

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
