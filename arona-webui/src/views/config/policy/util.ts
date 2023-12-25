import { TreeGraph } from "@antv/g6";
import { FormItemRule } from "element-plus";
import { Policy, PolicyNode, PolicyRule, PolicyRuleOperator, PolicyRuleType } from "@/interface";

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
    value: Array.isArray(node.value) ? node.value.join(",") : node.value,
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

interface PolicyRuleTestResult {
  id: string;
  stats: PolicyTestResultStatus;
}

export interface PolicyNodeTestResult {
  id: string;
  stats: PolicyTestResultStatus;
  children: (PolicyNodeTestResult | PolicyRuleTestResult)[];
}

export interface PolicyRootTestResult {
  id: string;
  stats: PolicyTestResultStatus;
  children: PolicyNodeTestResult[];
}

export type PolicyInput = {
  [key in PolicyRuleType]: unknown;
};

export interface PolicyTestInput extends PolicyInput {
  Resource: Resource;
  Action: Action;
  Subject: Subject;
  Environment: Environment;
}

type IPolicyRuleTesterOperatorValueType = {
  [key in PolicyRuleOperator]: unknown;
};

interface PolicyRuleTesterOperatorValueType extends IPolicyRuleTesterOperatorValueType {
  Equal: string;
  LessThan: string;
  GreaterThan: string;
  LessThanEqual: string;
  GreaterThanEqual: string;
  Contains: string;
  ContainsAll: string[];
  ContainsAny: string[];
  IsIn: string[];
  IsChild: string;
}

type IPolicyRuleTester = {
  [key in keyof PolicyTestInput]: {
    [k in keyof PolicyTestInput[key]]: <O extends keyof PolicyRuleTesterOperatorValueType>(
      value: PolicyRuleTesterOperatorValueType[O],
      operator: O,
      input: PolicyTestInput,
    ) => PolicyTestResultStatus;
  };
};

function transferStatus(b: boolean): PolicyTestResultStatus {
  return b ? "allow" : "deny";
}

function simpleCompare(value: string | string[], operator: PolicyRuleOperator, input: string): PolicyTestResultStatus {
  switch (operator) {
    case "Equal": {
      return transferStatus(input === (value as string));
    }
    case "LessThan": {
      return transferStatus(input < (value as string));
    }
    case "GreaterThan": {
      return transferStatus(input > (value as string));
    }
    case "LessThanEqual": {
      return transferStatus(input <= (value as string));
    }
    case "GreaterThanEqual": {
      return transferStatus(input >= (value as string));
    }
    default: {
      break;
    }
  }
  return "deny";
}

const PolicyRuleTester: IPolicyRuleTester = {
  Resource: {
    id(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      const compare = input.Resource.id;
      switch (operator) {
        case "Equal": {
          return transferStatus(compare === (value as string));
        }
        case "IsIn": {
          return transferStatus(Array.isArray(value) && value.includes(compare));
        }
        case "IsChild": {
          return transferStatus(testIsChild(compare, value));
        }
        default: {
          break;
        }
      }
      return "allow";
    },
  },
  Action: {
    action(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return "allow";
    },
  },
  Subject: {
    id(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      const compare = input.Subject.id;
      switch (operator) {
        case "Equal": {
          return transferStatus(compare === (value as string));
        }
        case "IsIn": {
          return transferStatus(Array.isArray(value) && value.includes(compare));
        }
        default: {
          break;
        }
      }
      return "allow";
    },
    roles(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      const compare = input.Subject.roles;
      switch (operator) {
        case "Contains": {
          return transferStatus(compare.includes(value as string));
        }
        case "ContainsAny": {
          return transferStatus(Array.isArray(value) && compare.some((it) => value.includes(it)));
        }
        case "ContainsAll": {
          return transferStatus(
            Array.isArray(value) && value.map((it) => compare.includes(it)).reduce((a, b) => a && b, true),
          );
        }
        case "IsIn": {
          return transferStatus(
            Array.isArray(value) && compare.map((it) => value.includes(it)).reduce((a, b) => a && b, true),
          );
        }
        default: {
          break;
        }
      }
      return "allow";
    },
  },
  Environment: {
    time(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return simpleCompare(value, operator, input.Environment.time);
    },
    date(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return simpleCompare(value, operator, input.Environment.date);
    },
    datetime(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return simpleCompare(value, operator, input.Environment.datetime);
    },
    param1(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return simpleCompare(value, operator, input.Environment.param1);
    },
    param2(value: string | string[], operator: PolicyRuleOperator, input: PolicyTestInput) {
      return simpleCompare(value, operator, input.Environment.param2);
    },
  },
};

function testPolicyRule(r: GraphPolicyRule, input: PolicyTestInput): PolicyRuleTestResult {
  // @ts-ignore
  const fn = PolicyRuleTester[r.oType][r.key];
  if (fn) {
    return {
      id: r.id,
      stats: fn(r.value, r.operator, input),
    };
  }
  return {
    id: r.id,
    stats: "deny",
  };
}

function testPolicyNode(r: GraphPolicyNode, input: PolicyTestInput): PolicyNodeTestResult {
  if (!r.children) {
    return {
      id: r.id,
      stats: "allow",
      children: [],
    };
  }
  const check = r.children.map((it) => {
    if (it.type === "policy-node") {
      return testPolicyNode(it, input);
    }
    return testPolicyRule(it as GraphPolicyRule, input);
  });
  switch (r.groupType) {
    case "ALL": {
      return {
        id: r.id,
        stats: check.some((it) => it.stats === "deny") ? "deny" : "allow",
        children: check,
      };
    }
    case "ANY": {
      return {
        id: r.id,
        stats: check.some((it) => it.stats === "allow") ? "allow" : "deny",
        children: check,
      };
    }
    case "NOT_ALL": {
      return {
        id: r.id,
        stats: !check.some((it) => it.stats === "deny") ? "deny" : "allow",
        children: check,
      };
    }
    case "NOT_ANY": {
      return {
        id: r.id,
        stats: !check.some((it) => it.stats === "allow") ? "allow" : "deny",
        children: check,
      };
    }
    default: {
      return {
        id: r.id,
        stats: "allow",
        children: check,
      };
    }
  }
}

export function testPolicy(r: GraphPolicyRoot, input: PolicyTestInput): PolicyRootTestResult {
  const check = r.children.map((it) => testPolicyNode(it, input));
  return {
    id: r.id,
    stats: check.some((it) => it.stats === "deny") ? "deny" : "allow",
    children: check,
  };
}

function testIsChild(left: unknown, right: unknown): boolean {
  if (!(typeof right === "string") || !(typeof left === "string")) {
    return false;
  }
  const rL = right.split(":");
  const lL = left.split(":");

  // eslint-disable-next-line no-inner-declarations,@typescript-eslint/no-shadow
  function testInner(right: string, left: string): boolean {
    if (right.endsWith("*")) {
      if (right === "*") {
        return true;
      }
      const leftList = left.split(".");
      const rightList = right.split(".");

      if (leftList.length < rightList.length) {
        return false;
      }
      if (leftList.length === rightList.length && rightList[rightList.length - 1] !== "*") {
        return left === right;
      }
      return (
        rightList
          .map((v, i) => leftList[i] === v)
          .slice(0, -1)
          .reduce((acc, b) => acc && b, true) && rightList[rightList.length - 1] === "*"
      );
    }
    return right === left;
  }

  if (rL.length > lL.length) {
    return false;
  }
  return rL.map((v, i) => testInner(v, lL[i])).reduce((acc, b) => acc && b, true);
}
