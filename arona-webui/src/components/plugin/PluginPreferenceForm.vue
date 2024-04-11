<script setup lang="ts">
import { ContactApi, PluginPreferenceApi } from "@/api";
import { errorMessage, IWarningConfirm, successMessage } from "@/utils/message";
import UserContactSwitcher from "@/views/config/plugin/component/UserContactSwitcher.vue";
import useBaseStore from "@/store/base";
import { Contact } from "@/interface";

defineOptions({
  name: "PluginPreferenceForm",
});
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
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    dataProcessor?: (data: any) => any;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    postDataProcessor?: (data: any) => any;
  }>(),
  {
    pId: "",
    pKey: "",
    form: () => ({}),
    defaultForm: () => ({}),
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    dataProcessor: (data: any) => data,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
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
let cacheProfileData: string;
const cacheMemberProfileData: { [key: string]: string } = {};
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function jsonParse(text: string): any {
  return props.dataProcessor(JSON.parse(text));
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
          if (cacheMemberProfileData[id]) {
            emits("update:form", jsonParse(cacheMemberProfileData[id]));
          } else {
            ContactApi.fetchMemberPluginPreference(id, props.pId, props.pKey).then((data) => {
              if (data) {
                cacheMemberProfileData[id] = data;
              }
              emits("update:form", data ? jsonParse(data) : props.defaultForm);
            });
          }
        }
        break;
      }
      case "manage-contact": {
        const tmp = contact.value?.config[props.pId];
        emits("update:form", tmp && tmp[props.pKey] ? jsonParse(tmp[props.pKey]) : props.defaultForm);
        break;
      }
      case "user": {
        emits("update:form", jsonParse(cacheProfileData));
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
      if (cacheMemberProfileData[cur]) {
        emits("update:form", jsonParse(cacheMemberProfileData[cur]));
      } else {
        ContactApi.fetchMemberPluginPreference(cur, props.pId, props.pKey).then((data) => {
          if (data && data !== "" && data !== "{}") {
            cacheMemberProfileData[cur] = data;
          }
          emits("update:form", data ? jsonParse(data) : props.defaultForm);
        });
      }
    } else if (editType.value.type === "manage-contact") {
      const tmp = contact.value?.config[props.pId];
      emits("update:form", tmp && tmp[props.pKey] ? jsonParse(tmp[props.pKey]) : props.defaultForm);
    }
  },
);
function onCancel() {
  formEl.value?.resetFields();
}
function onConfirm() {
  emits("confirm");
  const data = props.postDataProcessor(props.form);
  switch (editType.value.type) {
    case "contact": {
      ContactApi.updateMemberPluginPreference(editType.value.id, props.pId, props.pKey, data)
        .then(() => {
          cacheMemberProfileData[editType.value.id] = JSON.stringify(props.form);
          successMessage("保存成功");
        })
        .catch(() => {
          errorMessage("保存失败");
        });
      break;
    }
    case "user": {
      PluginPreferenceApi.updatePluginPreference(props.pId, props.pKey, data)
        .then(() => {
          cacheProfileData = JSON.stringify(props.form);
          successMessage("保存成功");
        })
        .catch(() => {
          errorMessage("保存失败");
        });
      break;
    }
    case "manage-contact": {
      ContactApi.updatePluginPreference(editType.value.id, props.pId, props.pKey, data)
        .then(() => {
          const tmp = contact.value?.config[props.pId];
          if (tmp) {
            tmp[props.pKey] = JSON.stringify(props.form);
          }
          successMessage("保存成功");
        })
        .catch(() => {
          errorMessage("保存失败");
        });
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
      emits("update:form", jsonParse(cacheProfileData));
    } else if (source === "contact") {
      if (cacheMemberProfileData[id]) {
        emits("update:form", jsonParse(cacheMemberProfileData[id]));
      } else {
        ContactApi.fetchMemberPluginPreference(id, props.pId, props.pKey).then((data) => {
          cacheMemberProfileData[id] = data;
          emits("update:form", data ? jsonParse(data) : props.defaultForm);
        });
      }
    } else {
      const tmp = contact.value?.config[props.pId];
      if (tmp) {
        emits("update:form", tmp[props.pKey] ? jsonParse(tmp[props.pKey]) : props.defaultForm);
      }
    }
  });
}
onMounted(() => {
  ContactApi.fetchContacts().then((data) => {
    contacts.value = data;
  });
  PluginPreferenceApi.fetchPluginPreference(props.pId, props.pKey).then((data) => {
    if (data && data !== "" && data !== "{}") {
      cacheProfileData = data;
      emits("update:form", jsonParse(data));
    } else {
      cacheProfileData = JSON.stringify(props.defaultForm);
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
