<template>
  <ElTabs v-model="tab">
    <ElTabPane name="base" label="基础设置">
      <PluginPreferenceForm
        v-model:form="baseConfig"
        :default-form="defaultBaseConfig"
        p-id="com·diyigemt·arona"
        p-key="BaseConfig"
      >
        <template #default="{ from }">
          <ElFormItem v-if="from === 'user'" label="启用markdown" prop="enable">
            <ElSwitch
              v-model="markdownConfig.enable"
              :active-value="true"
              :inactive-value="false"
              active-text="启用"
              inactive-text="停用"
            />
          </ElFormItem>
        </template>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="menu" label="快捷菜单设置">
      <PluginPreferenceForm
        v-model:form="menuConfig"
        :default-form="defaultCustomMenuConfig"
        p-id="com·diyigemt·arona·custom·menu"
        p-key="CustomMenuConfig"
      >
        <template #default="{ from }">
          <CustomMenu v-if="from !== 'manage-contact'" :data="defaultCustomMenuConfig" class="w-600px" />
        </template>
      </PluginPreferenceForm>
    </ElTabPane>
  </ElTabs>
</template>

<script setup lang="ts">
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import { CustomMenuConfig } from "@/interface";
import CustomMenu from "@/views/config/plugin/component/CustomMenu.vue";

defineOptions({
  name: "BasePreferences",
});
interface MarkdownCompatiblyConfig {
  enable: boolean;
}
interface BaseConfig {
  markdown: MarkdownCompatiblyConfig;
}
const tab = ref<"base" | "menu">("base");
const defaultBaseConfig: BaseConfig = {
  markdown: {
    enable: false,
  },
};
const defaultCustomMenuConfig: CustomMenuConfig = {
  rows: [
    {
      buttons: [
        { label: "官服总力档线", data: "/总力档线 官服", enter: true },
        { label: "B服总力档线", data: "/总力档线 B服", enter: true },
      ],
    },
    {
      buttons: [
        { label: "日服总力档线", data: "/总力档线 日服", enter: true },
        { label: "日服大决战档线", data: "/大决战档线 日服", enter: true },
      ],
    },
    {
      buttons: [
        { label: "国际服未来视", data: "/攻略 未来视", enter: true },
        { label: "国服日程", data: "/攻略 国服日程", enter: true },
      ],
    },
    {
      buttons: [
        { label: "日服总力", data: "/攻略 日服总力", enter: true },
        { label: "国际服总力", data: "/攻略 国际服总力", enter: true },
      ],
    },
    {
      buttons: [
        { label: "塔罗牌", data: "/塔罗牌", enter: true },
        { label: "十连", data: "/十连", enter: true },
      ],
    },
  ],
};
const baseConfig = ref<BaseConfig>(defaultBaseConfig);
const markdownConfig = computed({
  get() {
    return baseConfig.value.markdown;
  },
  set(val) {
    baseConfig.value.markdown = val;
  },
});
const menuConfig = ref(defaultCustomMenuConfig);
</script>

<style lang="scss" scoped></style>
