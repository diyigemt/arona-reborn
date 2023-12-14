<template>
  <div>Arona Preferences</div>
</template>

<script setup lang="ts">
import { PluginPreferenceApi } from "@/api/modules/pluginPreferences";
import { HTTP_OK } from "@/constant";

defineOptions({
  name: "AronaPreferences",
});
interface MarkdownCompatiblyConfig {
  enable: boolean;
}
const preference = ref<MarkdownCompatiblyConfig>();
PluginPreferenceApi.fetchPluginPreference("com.diyigemt.arona", "MarkdownCompatiblyConfig").then((data) => {
  if (data.code === HTTP_OK) {
    preference.value = JSON.parse(data.data);
  }
});
</script>

<style lang="scss" scoped></style>
