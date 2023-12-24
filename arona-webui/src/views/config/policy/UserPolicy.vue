<template>
  <div class="text-xl">编辑群 {{ contact.contactName }} 的策略</div>
  <ElForm inline class="mt-4">
    <ElFormItem label="策略选择:">
      <ElSelect v-model="selectPolicyId" @change="onPolicyChange">
        <ElOption v-for="(e, index) in policies" :key="index" :label="e.name" :value="e.id" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem>
      <ElButton @click="onCreate">创建</ElButton>
      <ElButton type="danger" @click="onReset">重置</ElButton>
      <ElButton type="primary" @click="onSave">保存</ElButton>
      <ElButton type="success" @click="onTest">测试</ElButton>
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
      <ElFormItem prop="name" label="对象">
        <ElSelect v-model="policyRuleForm.type" class="w-full" @change="onPropertySelectChange('object')">
          <ElOption v-for="e in PolicyRuleObjectSelect" :key="e" :label="e" :value="e" :disabled="e === 'Action'" />
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
  <CancelConfirmDialog v-model:show="showTestDataForm" title="测试数据" width="600" @confirm="doTest">
    <PolicyTestDataBuilder
      ref="testDataBuilder"
      :roles="contact.roles"
      :members="contact.members"
      :resources="resources"
    />
  </CancelConfirmDialog>
</template>

<script setup lang="ts">
import { IG6GraphEvent, TreeGraph } from "@antv/g6";
import { Ref } from "vue";
import { FormValidateCallback } from "element-plus";
import dayjs from "dayjs";
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
import { initGraph } from "@/views/config/policy/graph";
import { IAlert, IConfirm } from "@/utils/message";
import { randomInt, useForceUpdate } from "@/utils";
import {
  GraphPolicyRoot,
  mapPolicyToGraph, PolicyNodeTestResult,
  PolicyRuleFormRule,
  PolicyTestInput, PolicyTestResultStatus,
  testPolicy,
} from "@/views/config/policy/util";
import PolicyTestDataBuilder from "@/views/config/policy/component/PolicyTestDataBuilder.vue";

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
const policyId = route.query.pid as string;
// @ts-ignore
const contact = ref<Contact>({ contactName: "", roles: [], members: [] }) as Ref<Contact>;
const container = ref<HTMLDivElement>();
const testDataBuilder = ref<{ build(): PolicyTestInput }>();
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
const showRuleAddBtn = computed(() => parentNodeId.value !== policy.value.id);
const { visible, update } = useForceUpdate();
const showPolicyBaseForm = ref(false);
const policyBaseForm = ref<PolicyBaseForm>({ name: "", effect: "ALLOW" });
const showPolicyNodeForm = ref(false);
const policyNodeForm = ref<PolicyNodeForm>({ type: "modify", groupType: "ALL" });
const showPolicyRuleForm = ref(false);
const RuleForm = ref() as Ref<{ validate: (callback?: FormValidateCallback) => Promise<void> }>;
const showTestDataForm = ref(false);
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
      return ["time", "date", "datetime"];
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
    // eslint-disable-next-line no-nested-ternary
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
let destroyGraphHandler: () => void;
let graph: TreeGraph;
let graphData: GraphPolicyRoot;
function onPolicyChange(id: string) {
  IConfirm("警告", "确认要切换编辑的策略吗，所有修改将会丢失").then(() => {
    policy.value = policies.value.find((it) => it.id === id) as Policy;
    graphData = mapPolicyToGraph(policy.value);
    graph.changeData(graphData);
  });
}
function onPolicyBaseChange() {
  graphData.name = policyBaseForm.value.name;
  graphData.effect = policyBaseForm.value.effect;
  graph.updateItem(parentNodeId.value, {
    form: true,
  });
}
function onPolicyNodeChange() {
  const item = graph.findById(parentNodeId.value);
  const nodeModel = item.getModel();
  if (policyNodeForm.value.type === "modify") {
    nodeModel.groupType = policyNodeForm.value.groupType;
    graph.updateItem(parentNodeId.value, {
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
      parentNodeId.value,
    );
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
      const item = graph.findById(parentNodeId.value);
      const nodeModel = item.getModel();
      if (policyRuleForm.value.formType === "modify") {
        nodeModel.oType = policyRuleForm.value.type;
        nodeModel.operator = policyRuleForm.value.operator;
        nodeModel.key = policyRuleForm.value.key;
        nodeModel.value = policyRuleForm.value.value;
        graph.updateItem(parentNodeId.value, {
          form: true,
        });
      } else {
        const parent = graph.findById(nodeModel.parent as string);
        const parentModel = policyRuleForm.value.formType === "append" ? parent.getModel() : nodeModel;
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
      resolve();
    });
  });
}

function onCreate() {
  IConfirm("警告", "确认要创建吗，所有修改将会丢失").then(() => {
    policy.value = {
      id: `policy.${randomInt(0, 100)}`,
      name: "新建策略",
      effect: "ALLOW",
      rules: [],
    };
    selectPolicyId.value = policy.value.id;
    graphData = mapPolicyToGraph(policy.value);
    graph.changeData(graphData);
  });
}

function onReset() {
  if (policyId) {
    onPolicyChange(selectPolicyId.value);
  } else {
    onCreate();
  }
}

function onSave() {
  if (policyId) {
    // 修改
  } else {
    // 创建
  }
}

function onTest() {
  showTestDataForm.value = true;
}

function doTest() {
  const testData = testDataBuilder.value?.build();
  if (testData) {
    const testResult = testPolicy(graphData, testData);
    updateItemByResult(testResult);
  }
}

function updateItemByResult(result: PolicyNodeTestResult) {
  graph.updateItem(result.id, {
    status: result.stats,
  });
  if (result.children) {
    result.children.forEach((it) => updateItemByResult(it as PolicyNodeTestResult));
  }
}

onMounted(() => {
  if (!contactId) {
    IAlert("错误", "请从我管理的群->策略->新增进入本页面", { type: "warning" }).then(() => {
      router.go(-1);
    });
    return;
  }
  ContactApi.fetchContact(contactId).then((data) => {
    contact.value = data;
    if (policyId) {
      selectPolicyId.value = policyId;
      policy.value = policies.value.find((it) => it.id === policyId) as Policy;
      policyBaseForm.value.effect = policy.value.effect;
      policyBaseForm.value.name = policy.value.name;
    }
    graphData = mapPolicyToGraph(policy.value);
    initPolicyEdit();
  });
  PolicyApi.fetchResources().then((data) => {
    data.push("*");
    data.sort((a, b) => a.length - b.length);
    resources.value = data;
  });
});

function initPolicyEdit() {
  const { graph: g, destroy } = initGraph(container.value!, graphData);
  g.on("edit-text:click", (e: IG6GraphEvent) => {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    parentNodeId.value = nodeModel.id as string;
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
    parentNodeId.value = nodeModel.id as string;
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
  destroyGraphHandler && destroyGraphHandler();
});
</script>

<style lang="scss" scoped>
.container {
  border: 1px solid #dcdfe6;
}
</style>
