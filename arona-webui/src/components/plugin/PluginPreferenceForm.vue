<script setup lang="ts">
import { PluginPreferenceApi } from "@/api";
import { HTTP_OK } from "@/constant";
import { successMessage } from "@/utils/message";

defineOptions({
  name: "PluginPreferenceForm",
});
const props = defineProps<{
  pId: string;
  pKey: string;
  form: Record<string, never> | object;
}>();
const emits = defineEmits<{ (e: "confirm"): void }>();
const elForm = ref<{ resetFields(): void }>();
function onCancel() {
  elForm.value?.resetFields();
}
function onConfirm() {
  emits("confirm");
  PluginPreferenceApi.updatePluginPreference(props.pId, props.pKey, props.form).then((res) => {
    if (res.code === HTTP_OK) {
      successMessage("保存成功");
    }
  });
}
</script>

<template>
  <ElForm ref="elForm" :model="props.form">
    <slot />
    <ElFormItem>
      <ElButton @click="onCancel">取消</ElButton>
      <ElButton type="primary" @click="onConfirm">提交</ElButton>
    </ElFormItem>
  </ElForm>
</template>

<style scoped lang="scss"></style>
