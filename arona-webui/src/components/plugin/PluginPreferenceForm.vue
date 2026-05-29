<script setup lang="ts">
import { ContactApi, PluginPreferenceApi } from "@/api";
import { errorMessage, IWarningConfirm, successMessage } from "@/utils/message";
import UserContactSwitcher from "@/views/config/plugin/component/UserContactSwitcher.vue";
import useBaseStore from "@/store/base";
import { Contact } from "@/interface";
import type { BusinessError, FieldError } from "@/interface/pluginSchema";

defineOptions({
  name: "PluginPreferenceForm",
});

const fieldErrors = ref<FieldError[]>([]);
provide("fieldErrors", fieldErrors);

function reportSaveError(err: unknown) {
  // simplifiedApiService 现在 reject 一个结构化 BusinessError; 同时兼容老的裸字符串/Error.
  if (err && typeof err === "object" && "message" in err) {
    const be = err as BusinessError;
    errorMessage(be.message || "保存失败");
    fieldErrors.value = be.fieldErrors ?? [];
    return;
  }
  errorMessage(typeof err === "string" ? err || "保存失败" : "保存失败");
  fieldErrors.value = [];
}
interface ProfileType {
  id: string;
  type: "user" | "contact" | "manage-contact";
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
const contact = computed(() => contacts.value.find((it) => it.id === editType.value.id));
const editType = ref<ProfileType>({
  id: baseStore.userId,
  type: "user",
});
const showImportForm = ref(false);
interface ImportForm {
  id: string;
  source: "user" | "contact" | "manage-contact";
}
const importForm = ref<ImportForm>({ source: "contact", id: "" });
const formEl = ref<{ resetFields(): void }>();
// 后端 wire 已切结构化 JsonObject, 缓存改为对象副本而非字符串. 字符串时代是后端把对象 JSON.stringify
// 进 wire / 前端再 JSON.parse 兜出来, 缓存只能存字符串. 现在直接存对象, 避免反复 parse/stringify.
let cacheProfileData: Record<string, unknown> | undefined;
const cacheMemberProfileData: Record<string, Record<string, unknown>> = {};

// 深拷贝快照: 表单 v-model 直接写到响应式对象上, 若 cache 持有同引用则下一次"取消编辑"会拿到已脏数据.
// 配置 JSON 不会含 undefined / 函数 / 循环, 用 JSON 深拷贝最朴素够用.
function cloneConfig(data: object): Record<string, unknown> {
  return JSON.parse(JSON.stringify(data)) as Record<string, unknown>;
}

// "无数据" 判定: null / undefined / 空对象都视为没有持久化, 走 defaultForm. 历史用 "" / "{}" 哨兵已下线.
function hasConfigData(data: Record<string, unknown> | null | undefined): data is Record<string, unknown> {
  return data != null && typeof data === "object" && Object.keys(data).length > 0;
}

// 字符串时代 form / cache 都是字符串, 天然 immutable; 切对象 wire 后, fetch 出的对象若直接进 cache
// 再 emit 给父表单, v-model 的原地 mutate 会反向污染 cache. 入 cache 时存独立快照, emit 时给 form
// 独立副本, 双方互不污染.
function emitForm(data: object) {
  emits("update:form", props.dataProcessor(cloneConfig(data)));
}
const updateTrigger = computed(() => [editType.value.type, editType.value.id]);
provide("updateTrigger", updateTrigger);
watch(
  () => editType.value.type,
  (cur, prv) => {
    switch (cur) {
      case "contact": {
        if (prv === "manage-contact") {
          const { id } = editType.value;
          const cached = cacheMemberProfileData[id];
          if (cached) {
            emitForm(cached);
          } else {
            ContactApi.fetchMemberPluginPreference(id, props.pId, props.pKey).then((data) => {
              if (hasConfigData(data)) {
                cacheMemberProfileData[id] = cloneConfig(data);
              }
              emitForm(hasConfigData(data) ? data : props.defaultForm);
            });
          }
        }
        break;
      }
      case "manage-contact": {
        const leaf = contact.value?.config?.[props.pId]?.[props.pKey];
        emitForm(hasConfigData(leaf) ? leaf : props.defaultForm);
        break;
      }
      case "user": {
        emitForm(cacheProfileData ?? props.defaultForm);
        break;
      }
      default: {
        break;
      }
    }
  },
);
watch(
  () => editType.value.id,
  (cur) => {
    if (editType.value.type === "contact") {
      const cached = cacheMemberProfileData[cur];
      if (cached) {
        emitForm(cached);
      } else {
        ContactApi.fetchMemberPluginPreference(cur, props.pId, props.pKey).then((data) => {
          if (hasConfigData(data)) {
            cacheMemberProfileData[cur] = cloneConfig(data);
          }
          emitForm(hasConfigData(data) ? data : props.defaultForm);
        });
      }
    } else if (editType.value.type === "manage-contact") {
      const leaf = contact.value?.config?.[props.pId]?.[props.pKey];
      emitForm(hasConfigData(leaf) ? leaf : props.defaultForm);
    }
  },
);
function onCancel() {
  formEl.value?.resetFields();
}
function onConfirm() {
  emits("confirm");
  fieldErrors.value = [];
  const data = props.postDataProcessor(props.form);
  switch (editType.value.type) {
    case "contact": {
      ContactApi.updateMemberPluginPreference(editType.value.id, props.pId, props.pKey, data)
        .then(() => {
          cacheMemberProfileData[editType.value.id] = cloneConfig(props.form);
          successMessage("保存成功");
        })
        .catch(reportSaveError);
      break;
    }
    case "user": {
      PluginPreferenceApi.updatePluginPreference(props.pId, props.pKey, data)
        .then(() => {
          cacheProfileData = cloneConfig(props.form);
          successMessage("保存成功");
        })
        .catch(reportSaveError);
      break;
    }
    case "manage-contact": {
      ContactApi.updatePluginPreference(editType.value.id, props.pId, props.pKey, data)
        .then(() => {
          const tmp = contact.value?.config?.[props.pId];
          if (tmp) {
            tmp[props.pKey] = cloneConfig(props.form);
          }
          successMessage("保存成功");
        })
        .catch(reportSaveError);
      break;
    }
    default: {
      break;
    }
  }
}
function onImport() {
  showImportForm.value = true;
}
function onConfirmImport() {
  IWarningConfirm("警告", "已有内容将会被覆盖, 是否继续?").then(() => {
    const { id, source } = importForm.value;
    if (source === "user") {
      emitForm(cacheProfileData ?? props.defaultForm);
    } else if (source === "contact") {
      const cached = cacheMemberProfileData[id];
      if (cached) {
        emitForm(cached);
      } else {
        ContactApi.fetchMemberPluginPreference(id, props.pId, props.pKey).then((data) => {
          if (hasConfigData(data)) {
            cacheMemberProfileData[id] = cloneConfig(data);
          }
          emitForm(hasConfigData(data) ? data : props.defaultForm);
        });
      }
    } else {
      const leaf = contact.value?.config?.[props.pId]?.[props.pKey];
      emitForm(hasConfigData(leaf) ? leaf : props.defaultForm);
    }
  });
}
onMounted(() => {
  ContactApi.fetchContacts().then((data) => {
    contacts.value = data;
  });
  PluginPreferenceApi.fetchPluginPreference(props.pId, props.pKey).then((data) => {
    if (hasConfigData(data)) {
      cacheProfileData = cloneConfig(data);
      emitForm(data);
    } else {
      // 没持久化过, 缓存一份 defaultForm 的快照, 之后跨 type 切换时 "user" 分支能直接用上.
      cacheProfileData = cloneConfig(props.defaultForm);
    }
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
        <ElRadioGroup v-model="importForm.source">
          <ElRadioButton v-if="editType.type !== 'user'" label="user">自己</ElRadioButton>
          <ElRadioButton v-if="editType.type !== 'contact'" label="contact">群</ElRadioButton>
          <ElRadioButton v-if="editType.type !== 'manage-contact'" label="manage-contact">群默认</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem v-if="importForm.source !== 'user'" label="群">
        <ElSelect v-model="importForm.id" default-first-option>
          <ElOption v-for="(e, index) in contacts" :key="index" :label="e.contactName" :value="e.id" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </CancelConfirmDialog>
</template>

<style scoped lang="scss"></style>
