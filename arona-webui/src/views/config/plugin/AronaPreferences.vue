<template>
  <div class="mt-4">
    <PluginPreferenceForm
      v-model:form="preference"
      :default-form="defaultConfig"
      p-id="com.diyigemt.arona"
      p-key="MarkdownCompatiblyConfig"
    >
      <ElFormItem label="启用markdown" prop="enable">
        <ElSwitch
          v-model="preference.enable"
          :active-value="true"
          :inactive-value="false"
          active-text="启用"
          inactive-text="停用"
        />
      </ElFormItem>
    </PluginPreferenceForm>
  </div>
</template>

<script setup lang="ts">
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";

defineOptions({
  name: "AronaPreferences",
});
interface MarkdownCompatiblyConfig {
  enable: boolean;
}
const defaultConfig: MarkdownCompatiblyConfig = {
  enable: true,
};
const preference = ref<MarkdownCompatiblyConfig>(defaultConfig);
</script>

<style lang="scss" scoped></style>
