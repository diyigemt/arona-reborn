<template>
  <div class="text-xl">{{ contactId ? `编辑群${contact.contactName}的策略` : "创建策略" }}</div>
  <ElForm v-if="contactId" inline class="mt-4">
    <ElFormItem label="策略选择:">
      <ElSelect v-model="selectPolicyId" @change="onPolicyChange">
        <ElOption v-for="(e, index) in policies" :key="index" :label="e.name" :value="e.id" />
      </ElSelect>
    </ElFormItem>
  </ElForm>
  <div ref="container" class="w-100% h-500px rounded-5px container"></div>
  <CancelConfirmDialog v-model:show="showPolicyBaseForm" title="策略信息" width="700" @confirm="onPolicyBaseChange">
    <ElForm :model="policyBaseForm">
      <ElFormItem prop="name" label="策略名称">
        <ElInput v-model="policyBaseForm.name" maxlength="25" show-word-limit></ElInput>
      </ElFormItem>
      <ElFormItem prop="effect" label="策略动作">
        <ElSelect v-model="policyBaseForm.effect">
          <ElOption v-for="e in PolicyRootEffectSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
  <CancelConfirmDialog v-model:show="showPolicyNodeForm" title="策略节点" width="700" @confirm="onPolicyNodeChange">
    <ElForm :model="policyNodeForm">
      <ElFormItem prop="name" label="节点类型">
        <ElSelect v-model="policyNodeForm.groupType">
          <ElOption v-for="e in PolicyNodeTypeSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
  <CancelConfirmDialog v-model:show="showPolicyRuleForm" title="规则" width="700" @confirm="onPolicyRuleChange">
    <ElForm :model="policyRuleForm" label-width="120" label-position="left">
      <ElFormItem prop="name" label="对象">
        <ElSelect v-model="policyRuleForm.type" class="w-full" @change="onPropertySelectChange('object')">
          <ElOption v-for="e in PolicyRuleObjectSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="name" label="属性">
        <ElSelect v-model="policyRuleForm.key" class="w-full" @change="onPropertySelectChange('key')">
          <ElOption v-for="e in PolicyRulePropertySelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="name" label="操作符">
        <ElSelect v-model="policyRuleForm.operator" class="w-full" @change="onOperatorSelectChange">
          <ElOption v-for="e in PolicyRuleOperatorSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="name" label="值">
        <ElSelect
          v-if="isValueSelect && visible"
          v-model="policyRuleForm.value"
          class="w-full"
          filterable
          :multiple="isPolicyRuleValueArrayInput"
        >
          <ElOption v-for="(e, index) in PolicyRuleValueSelect" :key="index" :label="e.label" :value="e.value" />
        </ElSelect>
        <ElTimePicker
          v-else-if="isEnvironmentTimeSelect"
          v-model="policyRuleForm.value"
          value-format="HH:mm:ss"
          class="w-full"
        ></ElTimePicker>
        <ElDatePicker
          v-else-if="isEnvironmentDateTimeSelect && visible"
          v-model="policyRuleForm.value"
          :type="policyRuleForm.key"
          :value-format="policyRuleForm.key === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'"
          class="w-full"
        ></ElDatePicker>
        <ElInput v-else v-model="policyRuleForm.value"></ElInput>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
</template>

<script setup lang="ts">
import { IG6GraphEvent, TreeGraph } from "@antv/g6";
import { Ref } from "vue";
import { ContactApi, PolicyApi } from "@/api";
import {
  Policy,
  PolicyRootEffect,
  PolicyNodeGroupType,
  PolicyRuleType,
  PolicyRuleOperator,
  Contact,
  SelectOptions,
  PolicyResource, PolicyRule, PolicyNode,
} from "@/interface";
import { initGraph } from "@/views/config/policy/graph";
import { errorMessage, IConfirm } from "@/utils/message";
import { randomInt, useForceUpdate } from "@/utils";

defineOptions({
  name: "UserPolicy",
});
interface PolicyBaseForm {
  name: string;
  effect: PolicyRootEffect;
}
interface PolicyNodeForm {
  type: "modify" | "append";
  groupType: PolicyNodeGroupType;
}
interface PolicyRuleForm {
  formType: "modify" | "append";
  type: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  value: string | string[];
}
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
interface GraphPolicyRoot extends Policy, GraphDataBase {
  children: GraphPolicyNode[];
}
const PolicyRootEffectSelect: PolicyRootEffect[] = ["ALLOW", "DENY"];
const PolicyNodeTypeSelect: PolicyNodeGroupType[] = ["ALL", "ANY", "NOT_ALL", "NOT_ANY"];
const PolicyRuleObjectSelect: PolicyRuleType[] = ["Resource", "Action", "Subject", "Environment"];
const route = useRoute();
const contactId = route.query.id as string;
const policyId = route.query.pid as string;
// @ts-ignore
const contact = ref<Contact>({ contactName: "" }) as Ref<Contact>;
const container = ref<HTMLDivElement>();
const policy = ref<Policy>() as Ref<Policy>;
const selectPolicyId = ref<string>() as Ref<string>;
const resources = ref<PolicyResource[]>([]);
const policies = computed(() => contact.value.policies);
const members = computed(() => contact.value.members);
const roles = computed(() => contact.value.roles);
const { visible, update } = useForceUpdate();
const showPolicyBaseForm = ref(false);
const policyBaseForm = ref<PolicyBaseForm>({ name: "", effect: "ALLOW" });
const showPolicyNodeForm = ref(false);
const policyNodeForm = ref<PolicyNodeForm>({ type: "modify", groupType: "ALL" });
const showPolicyRuleForm = ref(false);
const policyRuleForm = ref<PolicyRuleForm>({
  formType: "modify",
  type: "Resource",
  operator: "Equal",
  key: "",
  value: "",
});
const PolicyRulePropertySelect = computed(() => {
  switch (policyRuleForm.value.type) {
    case "Resource": {
      return ["id"];
    }
    case "Action": {
      return ["id"];
    }
    case "Subject": {
      return ["id", "role"];
    }
    case "Environment": {
      return ["time", "date", "datetime"];
    }
    default: {
      return [];
    }
  }
});
const isValueSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  return (type === "Resource" && key === "id") || (type === "Subject" && ["id", "role"].includes(key));
});
const isEnvironmentTimeSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  return type === "Environment" && ["time"].includes(key);
});
const isEnvironmentDateTimeSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  return type === "Environment" && ["date", "datetime"].includes(key);
});
const isPolicyRuleValueArrayInput = computed(() => {
  return ["ContainsAll", "ContainsAny", "IsIn"].includes(policyRuleForm.value.operator);
});
const PolicyRuleOperatorSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  switch (type) {
    case "Resource": {
      switch (key) {
        case "id": {
          return ["Equal", "IsIn", "IsChild"];
        }
        default: {
          return [];
        }
      }
    }
    case "Action": {
      return [];
    }
    case "Subject": {
      switch (key) {
        case "id": {
          return ["Equal", "IsIn"];
        }
        case "role": {
          return ["Contains", "ContainsAll", "ContainsAny", "IsIn"];
        }
        default: {
          return [];
        }
      }
    }
    case "Environment": {
      return ["Equal", "LessThan", "GreaterThan", "LessThanEqual", "GreaterThanEqual"];
    }
    default: {
      return [];
    }
  }
});
const PolicyRuleValueSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  switch (type) {
    case "Resource": {
      switch (key) {
        case "id": {
          return resources.value.map((it) => {
            return {
              label: it,
              value: it,
            };
          });
        }
        default: {
          return [];
        }
      }
    }
    case "Action": {
      return [];
    }
    case "Subject": {
      switch (key) {
        case "id": {
          return members.value.map((it) => {
            return {
              label: it.name || it.id,
              value: it.id,
            };
          });
        }
        case "role": {
          return roles.value.map((it) => {
            return {
              label: it.name,
              value: it.id,
            };
          });
        }
        default: {
          return [];
        }
      }
    }
    case "Environment": {
      return [];
    }
    default: {
      return [];
    }
  }
});
function onPropertySelectChange(type: "object" | "key") {
  if (type === "object") {
    policyRuleForm.value.key = "";
  }
  if (isPolicyRuleValueArrayInput.value) {
    policyRuleForm.value.value = [];
  } else {
    policyRuleForm.value.value = "";
  }
  policyRuleForm.value.operator = PolicyRuleOperatorSelect.value[0] as PolicyRuleOperator;
  update();
}
function onOperatorSelectChange() {
  if (isPolicyRuleValueArrayInput.value) {
    policyRuleForm.value.value = Array.isArray(policyRuleForm.value.value)
      ? policyRuleForm.value.value
      : [policyRuleForm.value.value];
  } else {
    policyRuleForm.value.value = Array.isArray(policyRuleForm.value.value)
      ? policyRuleForm.value.value[0]
      : policyRuleForm.value.value;
  }
  update();
}
let parentNodeId: string;
let destroyGraphHandler: () => void;
let graph: TreeGraph;
let graphData: GraphPolicyRoot = {
  id: `policy.${randomInt(0, 100)}`,
  name: "新建策略",
  type: "policy-root",
  effect: "ALLOW",
  rules: [],
  parent: "",
  children: [],
};
function onPolicyChange(id: string) {
  IConfirm("警告", "确认要切换编辑的权限吗，所有修改将会丢失").then(() => {
    policy.value = policies.value.find((it) => it.id === policyId) as Policy;
    graph.layout();
  });
}
function onPolicyBaseChange() {
  graphData.name = policyBaseForm.value.name;
  graphData.effect = policyBaseForm.value.effect;
  graph.updateItem(parentNodeId, {
    form: true,
  });
}
function onPolicyNodeChange() {
  const item = graph.findById(parentNodeId);
  const nodeModel = item.getModel();
  if (policyNodeForm.value.type === "modify") {
    nodeModel.groupType = policyNodeForm.value.groupType;
    graph.updateItem(parentNodeId, {
      form: true,
    });
  } else {
    graph.addChild(
      {
        id: `${nodeModel.id}.node${((nodeModel.children as []) || []).length + 1}`,
        groupType: policyNodeForm.value.groupType,
        type: "policy-node",
        parent: parentNodeId,
        children: [],
      },
      parentNodeId,
    );
  }
}
function onPolicyRuleChange() {
  const item = graph.findById(parentNodeId);
  const nodeModel = item.getModel();
  if (policyRuleForm.value.formType === "modify") {
    nodeModel.oType = policyRuleForm.value.type;
    nodeModel.operator = policyRuleForm.value.operator;
    nodeModel.key = policyRuleForm.value.key;
    nodeModel.value = policyRuleForm.value.value;
    graph.updateItem(parentNodeId, {
      form: true,
    });
  } else {
    const parent = graph.findById(nodeModel.parent as string);
    const parentModel = parent.getModel();
    graph.addChild(
      {
        id: `${parentModel.id}.rule${((parentModel.children as []) || []).length + 1}`,
        type: "policy-rule",
        parent: parentModel.id,
        oType: policyRuleForm.value.type,
        operator: policyRuleForm.value.operator,
        key: policyRuleForm.value.key,
        value: policyRuleForm.value.value,
      },
      parentModel.id as string,
    );
  }
}
interface Tmp {
  id: string;
  children: Tmp[];
}
function onNodeRemove(id: string) {
  const father = findParentToRoot(id);
  if (father[0] !== graphData.id) {
    errorMessage("未知错误");
    return;
  }
  let tmp = graphData as unknown as Tmp;
  father.splice(0, 1);
  father.forEach((it) => {
    // eslint-disable-next-line prefer-destructuring
    tmp = tmp.children.filter((item) => item.id === it)[0];
  });
  const index = tmp.children.findIndex((it) => it.id === id);
  tmp.children.splice(index, 1);
}

onMounted(() => {
  if (contactId && policyId) {
    ContactApi.fetchContact(contactId).then((data) => {
      contact.value = data;
      selectPolicyId.value = policyId;
      policy.value = policies.value.find((it) => it.id === policyId) as Policy;
      policyBaseForm.value.effect = policy.value.effect;
      policyBaseForm.value.name = policy.value.name;
      graphData = mapPolicyToGraph(policy.value);
      initPolicyEdit();
    });
  } else {
    initPolicyEdit();
  }
  PolicyApi.fetchResources().then((data) => {
    resources.value = data;
  });
});
function findParentToRoot(id: string): string[] {
  if (!id) {
    return [];
  }
  const item = graph.findById(id);
  if (item) {
    const model = item.getModel();
    return [...findParentToRoot(model.parent as string), model.parent as string].filter((it) => it);
  }
  return [];
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
function mapPolicyToGraph(p: Policy): GraphPolicyRoot {
  const copy = JSON.parse(JSON.stringify(p)) as GraphPolicyRoot;
  copy.type = "policy-root";
  copy.children = [];
  (copy.rules || []).forEach((it) => {
    copy.children.push(mapPolicyNodeToGraph(it, copy));
  });
  return copy;
}
function mapGraphToPolicy(g: GraphPolicyRoot): Policy {

}
function initPolicyEdit() {
  const { graph: g, destroy } = initGraph(container.value!, graphData);
  g.on("edit-text:click", (e: IG6GraphEvent) => {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    parentNodeId = nodeModel.id as string;
    switch (nodeModel.type) {
      case "policy-root": {
        policyBaseForm.value.name = nodeModel.name as string;
        policyBaseForm.value.effect = nodeModel.effect as PolicyRootEffect;
        showPolicyBaseForm.value = true;
        break;
      }
      case "policy-node": {
        policyNodeForm.value.type = "modify";
        policyNodeForm.value.groupType = nodeModel.groupType as PolicyNodeGroupType;
        showPolicyNodeForm.value = true;
        break;
      }
      case "policy-rule": {
        policyRuleForm.value.formType = "modify";
        policyRuleForm.value.type = nodeModel.oType as PolicyRuleType;
        policyRuleForm.value.operator = nodeModel.operator as PolicyRuleOperator;
        policyRuleForm.value.key = nodeModel.key as string;
        policyRuleForm.value.value = nodeModel.value as string;
        showPolicyRuleForm.value = true;
        break;
      }
      default: {
        // nothing
      }
    }
  });
  g.on("append-child:click", (e: IG6GraphEvent) => {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    parentNodeId = nodeModel.id as string;
    switch (nodeModel.type) {
      case "policy-root": {
        policyNodeForm.value.type = "append";
        showPolicyNodeForm.value = true;
        break;
      }
      case "policy-node": {
        policyNodeForm.value.type = "append";
        showPolicyNodeForm.value = true;
        break;
      }
      case "policy-rule": {
        policyRuleForm.value.formType = "append";
        showPolicyRuleForm.value = true;
        break;
      }
      default: {
        // nothing
      }
    }
  });
  g.on("remove-self:click", (e: IG6GraphEvent) => {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    switch (nodeModel.type) {
      case "policy-root": {
        break;
      }
      case "policy-node":
      case "policy-rule": {
        graph.removeChild(id);
        setTimeout(() => {
          graph.layout(false);
        }, 1000);
        break;
      }
      default: {
        // nothing
      }
    }
  });
  graph = g;
  destroyGraphHandler = destroy;
}
onUnmounted(() => {
  destroyGraphHandler();
});
</script>

<style lang="scss" scoped>
.container {
  border: 1px solid #dcdfe6;
}
</style>
