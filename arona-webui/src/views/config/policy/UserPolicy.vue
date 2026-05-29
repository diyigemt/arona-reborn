<template>
  <div class="text-xl">编辑群 {{ contact.contactName }} 的策略</div>
  <ElForm inline class="mt-4 flex justify-between">
    <div class="inline-block">
      <ElFormItem label="策略选择:" class="w-300px">
        <ElSelect v-model="selectPolicyId" class="w-full" @change="onPolicyChange">
          <ElOption v-for="(e, index) in policies" :key="index" :label="e.name" :value="e.id" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem>
        <ElButton @click="onCreate">创建</ElButton>
        <ElButton type="danger" @click="onReset">重置</ElButton>
        <ElButton type="primary" @click="onSave">保存</ElButton>
        <ElButton type="success" @click="onTest">测试</ElButton>
        <ElButton type="primary" plain @click="onImportFromOtherContact">从其他群导入</ElButton>
      </ElFormItem>
    </div>
    <div class="inline-block">
      <ElButton type="danger" plain @click="onDelete">删除</ElButton>
    </div>
  </ElForm>
  <VueFlow
    :id="FLOW_ID"
    class="w-100% h-480px rounded-5px container"
    :nodes="nodes"
    :edges="edges"
    :node-types="nodeTypes"
    fit-view-on-init
    :nodes-draggable="false"
    :nodes-connectable="false"
    :elements-selectable="false"
    :select-nodes-on-drag="false"
    @nodes-initialized="onReady"
  >
    <Background />
    <Controls />
  </VueFlow>
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
    <ElForm :model="policyNodeForm" label-width="100" label-position="left">
      <ElFormItem prop="name" label="节点类型">
        <ElSelect v-model="policyNodeForm.groupType">
          <ElOption v-for="e in PolicyNodeTypeSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="showRuleAddBtn" prop="create">
        <ElButton @click="onPolicyRuleAddRule">新增规则</ElButton>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
  <CancelConfirmDialog
    v-model:show="showPolicyRuleForm"
    :on-before-confirm="onPolicyRuleChange"
    title="规则"
    width="700"
  >
    <ElForm ref="RuleForm" :model="policyRuleForm" :rules="PolicyRuleFormRule" label-width="120" label-position="left">
      <ElFormItem prop="type" label="对象">
        <ElSelect v-model="policyRuleForm.type" class="w-full" @change="onPropertySelectChange('object')">
          <ElOption v-for="e in PolicyRuleObjectSelect" :key="e" :label="e" :value="e" :disabled="e === 'Action'" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="key" label="属性">
        <ElSelect v-model="policyRuleForm.key" class="w-full" @change="onPropertySelectChange('key')">
          <ElOption v-for="e in PolicyRulePropertySelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="operator" label="操作符">
        <ElSelect v-model="policyRuleForm.operator" class="w-full" @change="onOperatorSelectChange">
          <ElOption v-for="e in PolicyRuleOperatorSelect" :key="e" :label="e" :value="e" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem prop="value" label="值">
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
  <CancelConfirmDialog v-model:show="showTestDataForm" title="测试数据" width="600" @confirm="doTest">
    <PolicyTestDataBuilder
      ref="testDataBuilder"
      :roles="contact.roles"
      :members="contact.members"
      :resources="resources"
    />
  </CancelConfirmDialog>
  <CancelConfirmDialog
    v-model:show="showImportFromOtherForm"
    title="从群导入"
    width="600"
    :on-before-confirm="onConfirmImport"
  >
    <ElForm :model="importForm" label-width="100" label-position="left">
      <ElFormItem label="群">
        <ElSelect v-model="importForm.id" class="w-full" @change="onOtherContactChange">
          <ElOption v-for="(e, index) in otherContacts" :key="index" :label="e.contactName" :value="e.id" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="策略">
        <ElSelect v-model="importForm.policy" class="w-full">
          <ElOption v-for="(e, index) in otherPolicies" :key="index" :label="e.name" :value="e.id" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
</template>

<script setup lang="ts">
import { markRaw, provide, type Ref } from "vue";
import { VueFlow, useVueFlow } from "@vue-flow/core";
import { Background } from "@vue-flow/background";
import { Controls } from "@vue-flow/controls";
import "@vue-flow/core/dist/style.css";
import "@vue-flow/controls/dist/style.css";
import { FormValidateCallback } from "element-plus";
import { ContactApi, PolicyApi } from "@/api";
import {
  Policy,
  PolicyRootEffect,
  PolicyNodeGroupType,
  PolicyRuleType,
  PolicyRuleOperator,
  Contact,
  PolicyResource,
} from "@/interface";
import { usePolicyGraph } from "@/views/config/policy/graph";
import { findById, type GraphNode, type GraphRule } from "@/views/config/policy/model";
import { POLICY_NODE_ACTIONS } from "@/views/config/policy/nodeActions";
import { errorMessage, IAlert, IWarningConfirm, successMessage } from "@/utils/message";
import { randomInt, useForceUpdate } from "@/utils";
import { mapGraphToPolicy, mapPolicyToGraph, PolicyRuleFormRule, PolicyTestInput } from "@/views/config/policy/util";
import PolicyTestDataBuilder from "@/views/config/policy/component/PolicyTestDataBuilder.vue";
import PolicyRootNode from "@/views/config/policy/nodes/PolicyRootNode.vue";
import PolicyNodeNode from "@/views/config/policy/nodes/PolicyNodeNode.vue";
import PolicyRuleNode from "@/views/config/policy/nodes/PolicyRuleNode.vue";

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
  formType: "modify" | "append" | "append-child";
  type: PolicyRuleType;
  operator: PolicyRuleOperator;
  key: string;
  value: string | string[];
}

const PolicyRootEffectSelect: PolicyRootEffect[] = ["ALLOW", "DENY"];
const PolicyNodeTypeSelect: PolicyNodeGroupType[] = ["ALL", "ANY", "NOT_ALL", "NOT_ANY"];
const PolicyRuleObjectSelect: PolicyRuleType[] = ["Resource", "Action", "Subject", "Environment"];
const route = useRoute();
const router = useRouter();
const contactId = route.query.id as string;
let policyId = route.query.pid as string;
// @ts-ignore
const contact = ref<Contact>({ contactName: "", roles: [], members: [] }) as Ref<Contact>;
const testDataBuilder = ref<{ build(): PolicyTestInput }>();
const FLOW_ID = "user-policy-graph";
const policyGraph = usePolicyGraph();
const { nodes, edges } = policyGraph;
// 与 <VueFlow :id> 同 id, 保证父级 fitView 操作的是同一个图实例。
const { fitView } = useVueFlow(FLOW_ID);
const nodeTypes = {
  "policy-root": markRaw(PolicyRootNode),
  "policy-node": markRaw(PolicyNodeNode),
  "policy-rule": markRaw(PolicyRuleNode),
};
function fitGraph() {
  // 重建后的节点需要一个 tick 完成挂载/测量, 再 fitView 才稳定。
  nextTick(() => fitView({ padding: 0.2, duration: 200 }));
}
function onReady() {
  fitGraph();
}
provide(POLICY_NODE_ACTIONS, {
  edit: onEditNode,
  append: onAppendNode,
  remove: onRemoveNode,
  toggleCollapse(id: string) {
    policyGraph.toggleCollapse(id);
    fitGraph();
  },
});
const policy = ref<Policy>({
  id: `policy.${randomInt(0, 100)}`,
  name: "新建策略",
  effect: "ALLOW",
  rules: [],
});
const selectPolicyId = ref<string>() as Ref<string>;
const resources = ref<PolicyResource[]>([]);
const policies = computed(() => contact.value.policies);
const members = computed(() => contact.value.members);
const roles = computed(() => contact.value.roles);
const showRuleAddBtn = computed(() => parentNodeId.value !== policy.value.id && policyNodeForm.value.type === "append");
const { visible, update } = useForceUpdate();
const showPolicyBaseForm = ref(false);
const policyBaseForm = ref<PolicyBaseForm>({ name: "", effect: "ALLOW" });
const showPolicyNodeForm = ref(false);
const policyNodeForm = ref<PolicyNodeForm>({ type: "modify", groupType: "ALL" });
const showPolicyRuleForm = ref(false);
const RuleForm = ref() as Ref<{ validate: (callback?: FormValidateCallback) => Promise<void> }>;
const showTestDataForm = ref(false);
const showImportFromOtherForm = ref(false); // 从其他地方导入
const importForm = ref({ id: "", policy: "" });
const otherContacts = ref<Contact[]>([]);
const otherPolicies = ref<Policy[]>([]);
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
      return ["id", "roles"];
    }
    case "Environment": {
      return ["time", "date", "datetime", "param1", "param2"];
    }
    default: {
      return [];
    }
  }
});
const isValueSelect = computed(() => {
  const { type, key } = policyRuleForm.value;
  return (type === "Resource" && key === "id") || (type === "Subject" && ["id", "roles"].includes(key));
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
        case "roles": {
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
        case "roles": {
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
      : policyRuleForm.value.value
        ? [policyRuleForm.value.value]
        : [];
  } else {
    policyRuleForm.value.value = Array.isArray(policyRuleForm.value.value)
      ? policyRuleForm.value.value[0]
      : policyRuleForm.value.value;
  }
  update();
}
const parentNodeId = ref("");

function onPolicyChange(id: string) {
  IWarningConfirm("警告", "确认要切换编辑的策略吗，所有修改将会丢失")
    .then(() => {
      policy.value = policies.value.find((it) => it.id === id) as Policy;
      policyBaseForm.value.name = policy.value.name;
      policyBaseForm.value.effect = policy.value.effect;
      policyGraph.setData(mapPolicyToGraph(policy.value));
      fitGraph();
    })
    .catch();
}
function onPolicyBaseChange() {
  policyGraph.update(parentNodeId.value, {
    name: policyBaseForm.value.name,
    effect: policyBaseForm.value.effect,
  });
}
function onPolicyNodeChange() {
  if (policyNodeForm.value.type === "modify") {
    policyGraph.update(parentNodeId.value, { groupType: policyNodeForm.value.groupType });
  } else {
    policyGraph.addChild(parentNodeId.value, {
      kind: "policy-node",
      groupType: policyNodeForm.value.groupType,
    });
    fitGraph();
  }
}
function onPolicyRuleAddRule() {
  showPolicyNodeForm.value = false;
  nextTick(() => {
    policyRuleForm.value.formType = "append-child";
    showPolicyRuleForm.value = true;
  });
}
function onPolicyRuleChange() {
  return new Promise<void>((resolve, reject) => {
    RuleForm.value.validate((isValid) => {
      if (!isValid) {
        reject();
        return;
      }
      if (policyRuleForm.value.formType === "modify") {
        policyGraph.update(parentNodeId.value, {
          oType: policyRuleForm.value.type,
          operator: policyRuleForm.value.operator,
          key: policyRuleForm.value.key,
          value: policyRuleForm.value.value,
        });
      } else {
        const current = findById(policyGraph.getData(), parentNodeId.value);
        if (!current) {
          reject();
          return;
        }
        // "append"(在 rule 上点 +): 在该 rule 的父节点下追加 sibling rule;
        // "append-child"(节点表单内"新增规则"): 在当前节点下追加 rule。
        const targetParentId =
          policyRuleForm.value.formType === "append" && current.kind === "policy-rule" ? current.parent : current.id;
        policyGraph.addChild(targetParentId, {
          kind: "policy-rule",
          oType: policyRuleForm.value.type,
          operator: policyRuleForm.value.operator,
          key: policyRuleForm.value.key,
          value: policyRuleForm.value.value,
        });
        fitGraph();
      }
      resolve();
    });
  });
}

function onCreate() {
  IWarningConfirm("警告", "确认要创建吗，所有修改将会丢失").then(() => {
    policy.value = {
      id: `policy.${randomInt(0, 100)}`,
      name: "新建策略",
      effect: "ALLOW",
      rules: [],
    };
    selectPolicyId.value = policy.value.id;
    policyGraph.setData(mapPolicyToGraph(policy.value));
    fitGraph();
  });
}

function onReset() {
  if (policyId) {
    onPolicyChange(selectPolicyId.value);
  } else {
    onCreate();
  }
}

function isEdit() {
  return contact.value.policies.some((it) => it.id === policyId);
}

function onSave() {
  const data = mapGraphToPolicy(policyGraph.getData());
  const fn = isEdit() ? ContactApi.updateContactPolicy : ContactApi.createContactPolicy;
  fn(contact.value.id, data).then((pid) => {
    policyId = pid;
    successMessage("保存成功");
    setTimeout(() => {
      fetchData();
    }, 1000);
  });
}

function onTest() {
  showTestDataForm.value = true;
}

function onDelete() {
  if (isEdit()) {
    const current = policyGraph.getData();
    IWarningConfirm("警告", `确认删除策略 ${current.name} 吗? 操作不可逆!`).then(() => {
      ContactApi.deleteContactPolicy(contact.value.id, current.id).then(() => {
        successMessage("成功");
        policyId = (policies.value.find((it) => it.id !== current.id) || {}).id || "";
        selectPolicyId.value = policyId;
        fetchData();
      });
    });
  } else {
    errorMessage("都没创建呢删除什么");
  }
}

function doTest() {
  const testData = testDataBuilder.value?.build();
  if (!testData) {
    return;
  }
  const current = mapGraphToPolicy(policyGraph.getData());
  PolicyApi.previewPolicy({
    policies: [current],
    subject: {
      id: testData.Subject.id,
      roles: testData.Subject.roles.join(","),
    },
    action: { type: testData.Action.action || "effect" },
    resource: { id: testData.Resource.id },
    environment: {
      time: testData.Environment.time,
      date: testData.Environment.date,
      datetime: testData.Environment.datetime,
      param1: testData.Environment.param1,
      param2: testData.Environment.param2,
    },
  }).then((resp) => {
    policyGraph.setStatus(policyGraph.getData().id, resp.decision);
    if (resp.decision === "allow") {
      successMessage(`allow: 命中 ${resp.hitPolicyId || "-"}`);
    } else {
      errorMessage(`deny: ${resp.reason}`);
    }
  });
}

function fetchData() {
  ContactApi.fetchContact(contactId).then((data) => {
    contact.value = data;
    if (isEdit()) {
      selectPolicyId.value = policyId;
      policy.value = policies.value.find((it) => it.id === policyId) as Policy;
      policyBaseForm.value.effect = policy.value.effect;
      policyBaseForm.value.name = policy.value.name;
    } else {
      policy.value = {
        id: `policy.${randomInt(0, 100)}`,
        name: "新建策略",
        effect: "ALLOW",
        rules: [],
      };
    }
    policyGraph.setData(mapPolicyToGraph(policy.value));
    fitGraph();
  });
}

onMounted(() => {
  if (!contactId) {
    IAlert("错误", "请从我管理的群->策略->新增进入本页面", { type: "warning" }).then(() => {
      router.go(-1);
    });
    return;
  }
  fetchData();
  PolicyApi.fetchResources().then((data) => {
    resources.value = data;
  });
});

// ── 节点内嵌按钮的动作处理（经 provide 注入给节点组件，按 nodeId 操作）──
function onEditNode(id: string) {
  const node = findById(policyGraph.getData(), id);
  if (!node) return;
  parentNodeId.value = node.id;
  switch (node.kind) {
    case "policy-root": {
      policyBaseForm.value.name = node.name;
      policyBaseForm.value.effect = node.effect;
      showPolicyBaseForm.value = true;
      break;
    }
    case "policy-node": {
      policyNodeForm.value.type = "modify";
      policyNodeForm.value.groupType = (node as GraphNode).groupType;
      showPolicyNodeForm.value = true;
      break;
    }
    case "policy-rule": {
      const rule = node as GraphRule;
      policyRuleForm.value.formType = "modify";
      policyRuleForm.value.type = rule.oType;
      policyRuleForm.value.operator = rule.operator;
      policyRuleForm.value.key = rule.key;
      policyRuleForm.value.value = rule.value;
      showPolicyRuleForm.value = true;
      break;
    }
  }
}

function onAppendNode(id: string) {
  const node = findById(policyGraph.getData(), id);
  if (!node) return;
  parentNodeId.value = node.id;
  switch (node.kind) {
    case "policy-root":
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
  }
}

function onRemoveNode(id: string) {
  const node = findById(policyGraph.getData(), id);
  if (!node || node.kind === "policy-root") return;
  policyGraph.remove(id);
  fitGraph();
}
function onConfirmImport() {
  return IWarningConfirm("警告", "确认要切导入策略吗，所有修改将会丢失").then(() => {
    policy.value = otherPolicies.value.find((it) => it.id === importForm.value.policy) as Policy;
    policyGraph.setData(mapPolicyToGraph(policy.value));
    selectPolicyId.value = policy.value.id;
    fitGraph();
  });
}
function onOtherContactChange(id: string) {
  ContactApi.fetchContactPolicies(id).then((data) => {
    otherPolicies.value = data;
  });
}
function onImportFromOtherContact() {
  showImportFromOtherForm.value = true;
  ContactApi.fetchContacts().then((res) => {
    otherContacts.value = res.filter((it) => {
      return it.members.some((m) => m.roles.some((r) => r === "role.admin"));
    });
  });
}
</script>

<style lang="scss" scoped>
.container {
  border: 1px solid #dcdfe6;
}
</style>
