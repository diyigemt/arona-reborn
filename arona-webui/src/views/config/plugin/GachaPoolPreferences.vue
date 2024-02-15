<template>
  <ElTabs v-model="tab">
    <ElTabPane name="base" label="基础设置">
      <PluginPreferenceForm
        v-model:form="gachaPoolConfig"
        :default-form="defaultGachaPoolConfig"
        p-id="com·diyigemt·arona"
        p-key="GachaConfig"
      >
        <template #default="{ from }">
          <ElFormItem v-if="from !== 'contact'" label="启用markdown" prop="enable"></ElFormItem>
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
  name: "GachaPoolPreferences",
});
interface GachaPoolConfig {
  pools: CustomPool[];
}
interface CustomPool {
  extendBase: boolean;
  pickup: number[];
  rate: GachaRate;
  pickupRate: GachaRate;
}
interface GachaRate {
  r: number;
  sr: number;
  ssr: number;
}
interface Student {
  id: number;
  name: string;
  limit: StudentLimitType;
  rarity: StudentRarity;
}
type StudentRarity = "R" | "SR" | "SSR";
type StudentLimitType = "Unique" | "Event" | "Permanent";
const tab = ref<"gacha">("gacha");
const defaultGachaPoolConfig: GachaPoolConfig = {
  pools: [],
};

const gachaPoolConfig = ref<GachaPoolConfig>(defaultGachaPoolConfig);
</script>

<style lang="scss" scoped></style>
