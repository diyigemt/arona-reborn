<template>
  <ElTabs v-model="tab">
    <ElTabPane name="base" label="基础设置">
      <PluginPreferenceForm
        v-model:form="markdownConfig"
        :default-form="defaultBaseConfig.markdown"
        p-id="com.diyigemt.arona"
        p-key="MarkdownCompatiblyConfig"
      >
        <ElFormItem label="启用markdown" prop="enable">
          <ElSwitch
            v-model="markdownConfig.enable"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="停用"
          />
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="trainer" label="攻略设置">
      <PluginPreferenceForm
        v-model:form="trainerConfig"
        :default-form="defaultBaseConfig.trainer"
        :data-processor="onParseTrainerOption"
        p-id="com.diyigemt.arona"
        p-key="TrainerConfig"
      >
        <ElFormItem label="启用markdown" prop="override">
          <ElTable :data="trainerOverrideConfig">
            <ElTableColumn prop="type" label="类型">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect v-model="row.type">
                    <ElOption value="RAW" label="RAW" />
                  </ElSelect>
                </div>
                <div v-else>
                  {{ row.type }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="name" label="原始值" width="300">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect v-model="row.name" allow-create collapse-tags />
                </div>
                <div v-else>
                  {{ row.name }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="name" label="覆盖值" width="300">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElSelect v-model="row.value" remote :remote-method="onSearchTrainer" :loading="trainerSearchLoading">
                    <ElOption
                      v-for="(e, index) in aronaTrainerOptions"
                      :key="index"
                      :value="e.value"
                      :label="e.label"
                    />
                  </ElSelect>
                </div>
                <div v-else>
                  {{ row.value }}
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="160">
              <template #default="{ row }">
                <div v-if="row.edit">
                  <ElButton type="primary" @click="onConfirm(row)">确认</ElButton>
                  <ElButton @click="onCancel(row)">取消</ElButton>
                </div>
                <div v-else>
                  <ElButton @click="onEdit(row)">编辑</ElButton>
                  <ElButton type="danger" @click="onDeleteOverride(row)">删除</ElButton>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
    <ElTabPane name="tarot" label="塔罗牌设置">
      <PluginPreferenceForm
        v-model:form="tarotConfig"
        :default-form="defaultBaseConfig.tarot"
        p-id="com.diyigemt.arona"
        p-key="TarotConfig"
      >
        <ElFormItem label="启用逆天改命" prop="fxxkDestiny">
          <ElSwitch
            v-model="tarotConfig.fxxkDestiny"
            :active-value="true"
            :inactive-value="false"
            active-text="启用"
            inactive-text="停用"
          />
        </ElFormItem>
      </PluginPreferenceForm>
    </ElTabPane>
  </ElTabs>
</template>

<script setup lang="ts">
import PluginPreferenceForm from "@/components/plugin/PluginPreferenceForm.vue";
import { AronaApi } from "@/api/modules/arona";
import { SelectOptions } from "@/interface";
import { useTableInlineEditor } from "@/utils";

defineOptions({
  name: "AronaPreferences",
});

interface MarkdownCompatiblyConfig {
  enable: boolean;
}

interface TarotConfig {
  fxxkDestiny: boolean;
}

interface TrainerConfig {
  override: {
    id: string;
    type: "RAW";
    name: string[];
    value: string;
    edit: boolean;
  }[];
}

interface BaseConfig {
  markdown: MarkdownCompatiblyConfig;
  trainer: TrainerConfig;
  tarot: TarotConfig;
}

const tab = ref<"base" | "trainer" | "tarot">("base");
const defaultBaseConfig: BaseConfig = {
  markdown: {
    enable: true,
  },
  trainer: {
    override: [],
  },
  tarot: {
    fxxkDestiny: false,
  },
};
const preference = ref<BaseConfig>(defaultBaseConfig);
const markdownConfig = computed(() => preference.value.markdown);
const trainerConfig = computed(() => preference.value.trainer);
const trainerOverrideConfig = computed(() => trainerConfig.value.override);
const tarotConfig = computed(() => preference.value.tarot);
const { onEdit, onCancel, onConfirm, cache: overrideItem } = useTableInlineEditor(trainerOverrideConfig);

const trainerSearchLoading = ref(false);
const aronaTrainerOptions = ref<SelectOptions>([]);

function onParseTrainerOption(data: TrainerConfig) {
  data.override.forEach((it) => {
    it.id = `${it.name.join("-")}-${it.value}`;
    it.edit = false;
  });
}
function onDeleteOverride(override: TrainerConfig["override"][0]) {
  const index = trainerOverrideConfig.value.findIndex((it) => it.id === override.id);
  if (index !== -1) {
    trainerOverrideConfig.value.splice(index);
  }
}
function onSearchTrainer(name: string) {
  if (name) {
    trainerSearchLoading.value = true;
    AronaApi.trainerImage(name).then((data) => {
      trainerSearchLoading.value = false;
      if (data) {
        aronaTrainerOptions.value = data.map((it) => ({ id: it.hash, value: it.name, label: it.name }));
      }
    });
  }
}
</script>

<style lang="scss" scoped></style>
