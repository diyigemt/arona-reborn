<template>
  <!-- superAdmin 标志持久化在 localStorage, 仅决定显示; 后端写端点按 @SuperAdminOnly 拦截 -->
  <ElResult
    v-if="!baseStore.user.superAdmin"
    icon="info"
    title="闲聊配置仅限超级管理员修改"
    sub-title="请联系管理员(QQ:1355247243)"
  />
  <template v-else>
    <ElAlert type="info" :closable="false" title="闲聊只读取「群默认」这一档配置, 自己 / 分群两档保存了也不生效" />
    <PluginPreferenceForm
      v-if="schema"
      v-model:form="config"
      :default-form="defaultConfig"
      :p-id="pluginId"
      :p-key="configKey"
      class="mt-16px"
    >
      <template #default>
        <DynamicConfigForm :schema="schema.fields" :model-value="config" :p-id="pluginId" :p-key="configKey" />
      </template>
    </PluginPreferenceForm>
  </template>
</template>

<script setup lang="ts">
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import DynamicConfigForm from "@/components/plugin/DynamicConfigForm.vue";
import { PluginPreferenceApi } from "@/api";
import type { PluginConfigSchema } from "@/interface/pluginSchema";
import useBaseStore from "@/store/base";

defineOptions({
  name: "ChatbotPreferences",
});

const baseStore = useBaseStore();
// pluginId 经 toMongodbKey() 把点替换为 "·"; configKey 取 KSerializer.descriptor.serialName 最后一段
const pluginId = "com·diyigemt·arona·chatbot";
const configKey = "ChatbotConfig";

const schema = ref<PluginConfigSchema>();
// 默认值直接取 schema 里的 defaultValue, 不在前端复制一份 Kotlin 默认值; 表单等 schema 到了再挂, 保证挂载时默认值已就绪.
const defaultConfig = computed<Record<string, unknown>>(() =>
  Object.fromEntries((schema.value?.fields ?? []).map((f) => [f.key, f.defaultValue])),
);
const config = ref<Record<string, unknown>>({});

onMounted(() => {
  PluginPreferenceApi.fetchPluginConfigSchema(pluginId, configKey).then((s) => {
    if (s) schema.value = s;
  });
});
</script>
