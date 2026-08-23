<script setup lang="ts">
import { Contact } from "@/interface";
import useBaseStore from "@/store/base";
import ToDocument from "@/components/common/ToDocument.vue";

defineOptions({
  name: "UserContactSwitcher",
});

const formData = defineModel<{
  id: string;
  type: "user" | "contact" | "manage-contact";
}>({ default: { id: "", type: "user" } });
const props = withDefaults(
  defineProps<{
    contacts: Contact[];
  }>(),
  {
    contacts: () => [],
  },
);
const emit = defineEmits<{ (e: "import"): void }>();
const baseStore = useBaseStore();
const isUser = computed(() => formData.value.type === "user");
const importButtonText = computed(() => {
  if (isUser.value) {
    return "从其他群导入";
  }
  return "从个人/其他群导入";
});
// 非 user 档的默认群由父组件挑: 这里的 contacts prop 随 type 变, 但重渲染在本回调之后, 此刻拿到的还是旧列表.
function onTypeChange() {
  formData.value.id = isUser.value ? baseStore.userId : "";
}
function onImport() {
  emit("import");
}
</script>

<template>
  <ElForm :model="formData" label-width="100" label-position="left" inline>
    <ElFormItem label="修改对象：">
      <ElRadioGroup v-model="formData.type" @change="onTypeChange">
        <ElRadioButton label="user">自己</ElRadioButton>
        <ElRadioButton label="contact">分群</ElRadioButton>
        <ElRadioButton label="manage-contact">群默认</ElRadioButton>
      </ElRadioGroup>
    </ElFormItem>
    <ElFormItem v-if="formData.type !== 'user'" class="w-240px!">
      <ElSelect v-model="formData.id">
        <ElOption v-for="(e, index) in contacts" :key="index" :label="e.contactName" :value="e.id" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem>
      <ElButton type="primary" plain @click="onImport">{{ importButtonText }}</ElButton>
      <ToDocument path="" />
    </ElFormItem>
  </ElForm>
</template>

<style scoped lang="scss"></style>
