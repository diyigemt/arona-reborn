<template>
  <PluginPreferenceForm
    v-model:form="config"
    :default-form="defaultConfig"
    :p-id="pluginId"
    :p-key="configKey"
  >
    <template #default>
      <DynamicConfigForm
        v-if="schema"
        :schema="schema.fields"
        :model-value="config"
        :p-id="pluginId"
        :p-key="configKey"
      />
    </template>
  </PluginPreferenceForm>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import DynamicConfigForm from "@/components/plugin/DynamicConfigForm.vue";
import { PluginPreferenceApi } from "@/api";
import type { PluginConfigSchema } from "@/interface/pluginSchema";

defineOptions({
  name: "CoffeePreferences",
});

// pluginId 经 toMongodbKey() 把点替换为 "·"; configKey 取 KSerializer.descriptor.serialName 最后一段
const pluginId = "com·diyigemt·kivotos";
const configKey = "CoffeeConfig";

const defaultConfig = { inviteDoubleCheck: true, touchAfterInvite: true };
const config = ref<Record<string, unknown>>({ ...defaultConfig });
const schema = ref<PluginConfigSchema>();

onMounted(() => {
  PluginPreferenceApi.fetchPluginConfigSchema(pluginId, configKey).then((s) => {
    if (s) schema.value = s;
  });
});
</script>
