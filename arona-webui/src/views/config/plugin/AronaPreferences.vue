<template>
  <div>Arona Preferences</div>
  <PluginPreferenceForm :form="preference" p-id="com.diyigemt.arona" p-key="MarkdownCompatiblyConfig">
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
</template>

<script setup lang="ts">
import { PluginPreferenceApi } from "@/api/modules/pluginPreferences";
import { HTTP_OK } from "@/constant";
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
PluginPreferenceApi.fetchPluginPreference("com.diyigemt.arona", "MarkdownCompatiblyConfig").then((data) => {
  if (data.code === HTTP_OK) {
    preference.value = JSON.parse(data.data);
  }
});
</script>

<style lang="scss" scoped></style>
