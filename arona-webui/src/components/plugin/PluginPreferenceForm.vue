<script setup lang="ts">
import { ContactApi, PluginPreferenceApi } from "@/api";
import { errorMessage, IWarningConfirm, successMessage, warningMessage } from "@/utils/message";
import UserContactSwitcher from "@/views/config/plugin/component/UserContactSwitcher.vue";
import useBaseStore from "@/store/base";
import { Contact } from "@/interface";
import type { BusinessError, FieldError } from "@/interface/pluginSchema";

defineOptions({
  name: "PluginPreferenceForm",
});

const fieldErrors = ref<FieldError[]>([]);
provide("fieldErrors", fieldErrors);

function reportError(err: unknown, fallback = "保存失败") {
  // simplifiedApiService 现在 reject 一个结构化 BusinessError; 同时兼容老的裸字符串/Error.
  if (err && typeof err === "object" && "message" in err) {
    const be = err as BusinessError;
    errorMessage(be.message || fallback);
    fieldErrors.value = be.fieldErrors ?? [];
    return;
  }
  errorMessage(typeof err === "string" ? err || fallback : fallback);
  fieldErrors.value = [];
}
type ProfileSource = "user" | "contact" | "manage-contact";
interface ProfileType {
  id: string;
  type: ProfileSource;
}
const props = withDefaults(
  defineProps<{
    pId: string;
    pKey: string;
    form: Record<string, never> | object;
    defaultForm: Record<string, never> | object;

    dataProcessor?: (data: any) => any;

    postDataProcessor?: (data: any) => any;
  }>(),
  {
    pId: "",
    pKey: "",
    form: () => ({}),
    defaultForm: () => ({}),

    dataProcessor: (data: any) => data,

    postDataProcessor: (data: any) => data,
  },
);
const emits = defineEmits<{
  (e: "confirm"): void;
  (e: "update:form", form: Record<string, never>): void;
}>();
const baseStore = useBaseStore();
const contacts = ref<Contact[]>([]);
const manageContacts = computed(() => {
  return contacts.value.filter((it) => {
    return it.members.some((m) => m.roles.some((r) => r === "role.admin"));
  });
});
const contactSelect = computed(() => {
  return editType.value.type === "contact" ? contacts.value : manageContacts.value;
});
const editType = ref<ProfileType>({
  id: baseStore.userId,
  type: "user",
});
const showImportForm = ref(false);
interface ImportForm {
  id: string;
  source: ProfileSource;
}
const importForm = ref<ImportForm>({ source: "contact", id: "" });
const formEl = ref<{ resetFields(): void }>();

// 深拷贝快照: 表单 v-model 直接写到响应式对象上, 若 cache 持有同引用则下一次"取消编辑"会拿到已脏数据.
// 配置 JSON 不会含 undefined / 函数 / 循环, 用 JSON 深拷贝最朴素够用.
function cloneConfig(data: object): Record<string, unknown> {
  return JSON.parse(JSON.stringify(data)) as Record<string, unknown>;
}

// "无数据" 判定: null / undefined / 空对象都视为没有持久化, 走 defaultForm. 历史用 "" / "{}" 哨兵已下线.
function hasConfigData(data: unknown): data is Record<string, unknown> {
  return data != null && typeof data === "object" && Object.keys(data).length > 0;
}

// 三档配置各一份独立快照, key = `${type}:${id}`. fetch 出的对象若直接进 cache 再 emit 给父表单,
// v-model 的原地 mutate 会反向污染 cache, 所以入 cache 与 emit 各给一份副本.
const cache: Record<string, Record<string, unknown>> = {};
function cacheKey(type: ProfileSource, id: string) {
  return `${type}:${id}`;
}
async function load(type: ProfileSource, id: string): Promise<Record<string, unknown>> {
  const key = cacheKey(type, id);
  // 没有可选的群 (非管理员点了群默认 / 列表还没回来): 给默认表单, 不缓存.
  if (type !== "user" && !id) return cloneConfig(props.defaultForm);
  if (!cache[key]) {
    const data =
      type === "user"
        ? await PluginPreferenceApi.fetchPluginPreference(props.pId, props.pKey)
        : type === "contact"
          ? await ContactApi.fetchMemberPluginPreference(id, props.pId, props.pKey)
          : // 列表接口 /contact/contacts 出于安全不再下发 config, 群默认要单独走管理员门控的 /contact/contact?id=
            (await ContactApi.fetchContact(id)).config?.[props.pId]?.[props.pKey];
    cache[key] = cloneConfig(hasConfigData(data) ? data : props.defaultForm);
  }
  return cache[key];
}
function emitForm(data: object) {
  emits("update:form", props.dataProcessor(cloneConfig(data)));
}
const updateTrigger = computed(() => [editType.value.type, editType.value.id]);
provide("updateTrigger", updateTrigger);
// loadedKey 挡住"列表晚到"这种 type/id 没变的重跑 (不然会把用户正在编辑的表单重置); seq 挡住先发后到的旧响应.
let loadedKey = "";
let seq = 0;
watch(
  () => [editType.value.type, editType.value.id, contactSelect.value] as const,
  ([type, id, list]) => {
    // 子组件改 type 时手里的 contacts prop 还是旧列表 (contactSelect 随 type 变, 重渲染在后), 这里纠正, 会再触发一次.
    // 列表还没加载完时 id 先落空, 等 fetchContacts 回来 contactSelect 变化也会走到这里补上.
    if (type !== "user" && !list.some((c) => c.id === id)) {
      const first = list[0]?.id ?? "";
      if (first !== id) {
        editType.value.id = first;
        return;
      }
    }
    const key = cacheKey(type, id);
    if (key === loadedKey) return;
    loadedKey = key;
    const mine = ++seq;
    load(type, id)
      .then((data) => mine === seq && emitForm(data))
      .catch((err) => reportError(err, "读取配置失败"));
  },
  { immediate: true },
);
function onCancel() {
  formEl.value?.resetFields();
}
function onConfirm() {
  emits("confirm");
  fieldErrors.value = [];
  const data = props.postDataProcessor(props.form);
  const { type, id } = editType.value;
  // 请求往返期间用户可能已切到别的档位, 回写 cache 要用发请求那一刻的快照.
  const snapshot = cloneConfig(props.form);
  const save =
    type === "user"
      ? PluginPreferenceApi.updatePluginPreference(props.pId, props.pKey, data)
      : type === "contact"
        ? ContactApi.updateMemberPluginPreference(id, props.pId, props.pKey, data)
        : ContactApi.updatePluginPreference(id, props.pId, props.pKey, data);
  save
    .then(() => {
      cache[cacheKey(type, id)] = snapshot;
      successMessage("保存成功");
    })
    .catch(reportError);
}
function onImport() {
  showImportForm.value = true;
}
function onConfirmImport() {
  IWarningConfirm("警告", "已有内容将会被覆盖, 是否继续?").then(() => {
    const { id, source } = importForm.value;
    if (source !== "user" && !id) {
      warningMessage("请先选择群");
      return;
    }
    const mine = seq;
    load(source, source === "user" ? baseStore.userId : id)
      // 等待期间切了档位就作废, 别把导入的内容盖到另一档上.
      .then((data) => mine === seq && emitForm(data))
      .catch((err) => reportError(err, "读取配置失败"));
  });
}
onMounted(() => {
  ContactApi.fetchContacts().then((data) => {
    contacts.value = data;
  });
});
</script>

<template>
  <UserContactSwitcher v-model="editType" :contacts="contactSelect" @import="onImport" />
  <ElForm ref="formEl" :model="props.form">
    <slot :from="editType.type" />
    <ElFormItem>
      <ElButton @click="onCancel">取消</ElButton>
      <ElButton type="primary" @click="onConfirm">提交</ElButton>
    </ElFormItem>
  </ElForm>
  <CancelConfirmDialog v-model:show="showImportForm" title="导入" width="600" @confirm="onConfirmImport">
    <ElForm :model="importForm" label-width="120" label-position="left">
      <ElFormItem label="来源">
        <ElRadioGroup v-model="importForm.source" @change="importForm.id = ''">
          <ElRadioButton v-if="editType.type !== 'user'" label="user">自己</ElRadioButton>
          <ElRadioButton v-if="editType.type !== 'contact'" label="contact">群</ElRadioButton>
          <ElRadioButton v-if="editType.type !== 'manage-contact'" label="manage-contact">群默认</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem v-if="importForm.source !== 'user'" label="群">
        <ElSelect v-model="importForm.id" default-first-option>
          <ElOption
            v-for="(e, index) in importForm.source === 'manage-contact' ? manageContacts : contacts"
            :key="index"
            :label="e.contactName"
            :value="e.id"
          />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
</template>

<style scoped lang="scss"></style>
